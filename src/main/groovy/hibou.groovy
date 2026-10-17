// SPDX-License-Identifier: Apache-2.0
/*
 * hibou-artifactory-plugin: Hibou's package policy (release cooldown,
 * malicious packages, acceptances) enforced in Artifactory. A thin adapter
 * over com.hibouhq.artifactory (plain Java, unit-tested): every hook is
 * wrapped, fails open, and never calls the network on the request thread.
 */
import com.hibouhq.artifactory.*
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.artifactory.exception.CancelException
import org.artifactory.repo.RepoPath

import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class HibouState {
    PluginConfig config
    DecisionCache cache
    CircuitBreaker breaker
    PolicyClient client
    Map<String, String> packageTypeByRepo = new java.util.concurrent.ConcurrentHashMap<>()
    Map<String, Map> filteredPackuments = Collections.synchronizedMap(new LinkedHashMap<String, Map>(256, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry eldest) { size() > 2000 }
    })
    AtomicLong allowed = new AtomicLong(), held = new AtomicLong(), denied = new AtomicLong(), observed = new AtomicLong(), errors = new AtomicLong()
    long lastErrorLogAt = 0
}

HibouState state = new HibouState()
File configFile = new File(ctx.artifactoryHome.etcDir, 'plugins/hibou.properties')

def parser = { String json ->
    def body = new JsonSlurper().parseText(json)
    def now = Instant.now()
    body.decisions.collect { d ->
        def verdict = d.decision == 'hold' ? Decision.Verdict.HOLD : d.decision == 'deny' ? Decision.Verdict.DENY : Decision.Verdict.ALLOW
        new Decision(d.purl as String, verdict, d.until ? Instant.parse(d.until as String) : null, d.reason as String, d.evaluated as boolean, now)
    }
} as PolicyClient.Parser

def reload = {
    def cfg = PluginConfig.load(Paths.get(configFile.path))
    state.client?.shutdown()
    state.config = cfg
    state.cache = new DecisionCache(cfg.cacheMax, cfg.cacheTtl, cfg.negativeTtl)
    state.breaker = new CircuitBreaker(cfg.breakerFailures, cfg.breakerOpen)
    state.client = cfg.enabled && cfg.url && cfg.org ? new PolicyClient(cfg.url, cfg.org, cfg.token, state.cache, state.breaker, parser, cfg.executorQueue, cfg.syncTimeout) : null
    state.packageTypeByRepo.clear()
    state.filteredPackuments.clear()
    log.info("hibou-plugin: loaded config enabled=${cfg.enabled} mode=${cfg.enforce ? 'enforce' : 'observe'} repos=${cfg.repos}")
}
reload()

// Logged once per minute at most: a plugin bug must never fail a download
// and must never flood the log either.
def countError = { Throwable t ->
    state.errors.incrementAndGet()
    long now = System.currentTimeMillis()
    if (now - state.lastErrorLogAt > 60_000) {
        state.lastErrorLogAt = now
        log.warn("hibou-plugin: internal error (download allowed): ${t}", t)
    }
}

def packageType = { String repoKey ->
    state.packageTypeByRepo.computeIfAbsent(repoKey) { key ->
        def conf = repositories.getRepositoryConfiguration(key)
        (conf?.type == 'remote' || conf?.type == 'virtual') ? (conf.packageType ?: '') : 'local'
    }
}

download {
    beforeDownload { Request request, RepoPath repoPath ->
        try {
            def cfg = state.config
            if (!cfg?.enabled || state.client == null) { return }
            if (!PreFilter.isArtifact(repoPath.repoKey, repoPath.path, cfg.repos)) { return }
            def type = packageType(repoPath.repoKey)
            if (type == 'local') { return }
            def purl = PurlMapper.toPurl(type, repoPath.path)
            if (!purl.isPresent()) { return }
            def now = Instant.now()
            def cached = state.cache.get(purl.get(), now)
            Decision decision = cached.orElse(null)
            if (decision == null) {
                decision = state.client.lookupSync(purl.get(), now)
                if (decision == null) {
                    state.client.lookupAsync([purl.get()])
                    decision = cfg.defaultHold ? new Decision(purl.get(), Decision.Verdict.HOLD, null, 'not yet evaluated (default: hold)', false, now) : Decision.allow(purl.get(), 'not yet evaluated')
                }
            }
            if (!decision.blocks()) { state.allowed.incrementAndGet(); return }
            // A per-version exemption is a property read, never written, by the plugin.
            def exempt = repositories.getProperty(repoPath, 'hibou.exempt')
            if (exempt) { state.allowed.incrementAndGet(); return }
            if (!cfg.enforce) {
                state.observed.incrementAndGet()
                log.info("hibou-plugin (observe): would refuse ${repoPath} - ${decision}")
                return
            }
            if (decision.verdict == Decision.Verdict.HOLD) { state.held.incrementAndGet() } else { state.denied.incrementAndGet() }
            // The reason is written once per decision change, never per request.
            if (repositories.getProperty(repoPath, 'hibou.decision') != decision.toString()) {
                repositories.setProperty(repoPath, 'hibou.decision', decision.toString())
            }
            throw new CancelException("hibou package policy: ${decision}", 403)
        } catch (CancelException cancel) {
            throw cancel
        } catch (Throwable t) {
            countError(t)
        }
    }

    // npm packument filtering (ADR-048 §3.2): behind its own flag, size-capped, cached.
    altResponse { Request request, RepoPath responseRepoPath ->
        try {
            def cfg = state.config
            if (!cfg?.enabled || !cfg.packumentFilter) { return }
            if (!PreFilter.isNpmPackument(responseRepoPath.repoKey, responseRepoPath.path, cfg.repos)) { return }
            if (packageType(responseRepoPath.repoKey) != 'npm') { return }
            def key = responseRepoPath.id
            def cached = state.filteredPackuments.get(key)
            if (cached != null && cached.until > System.currentTimeMillis()) {
                inputStream = new ByteArrayInputStream(cached.bytes as byte[])
                return
            }
            def content = repositories.getContent(responseRepoPath)
            if (content.size > cfg.packumentMaxBytes) { return }
            def text = content.inputStream.getText('UTF-8')
            def doc = new JsonSlurper().parseText(text)
            def result = PackumentFilter.filter(doc as Map, cfg.window, Instant.now(), Collections.emptySet())
            if (result.removed.isEmpty()) { return }
            if (!cfg.enforce) {
                log.info("hibou-plugin (observe): would hide ${result.removed.size()} young versions of ${responseRepoPath.path}")
                return
            }
            byte[] bytes = JsonOutput.toJson(result.document).getBytes('UTF-8')
            state.filteredPackuments.put(key, [bytes: bytes, until: System.currentTimeMillis() + 10 * 60_000])
            inputStream = new ByteArrayInputStream(bytes)
            size = bytes.length
        } catch (Throwable t) {
            countError(t)
        }
    }
}

executions {
    hibouReload(httpMethod: 'POST') { params ->
        try { reload(); message = 'reloaded'; status = 200 } catch (Throwable t) { message = t.toString(); status = 500 }
    }
    hibouStatus(httpMethod: 'GET') { params ->
        def cfg = state.config
        message = JsonOutput.toJson([enabled: cfg?.enabled, mode: cfg?.enforce ? 'enforce' : 'observe', repos: cfg?.repos, cache_size: state.cache?.size(), breaker_open: state.breaker?.isOpen()])
        status = 200
    }
    hibouMetrics(httpMethod: 'GET') { params ->
        def metrics = [allowed: state.allowed.get(), held: state.held.get(), denied: state.denied.get(), observed: state.observed.get(), errors: state.errors.get(),
                       cache_hits: state.cache?.hits?.get(), cache_misses: state.cache?.misses?.get(), cache_evictions: state.cache?.evictions?.get(), cache_size: state.cache?.size()]
        if (state.client) { metrics.putAll(state.client.metrics()) }
        message = JsonOutput.toJson(metrics)
        status = 200
    }
}
