// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Talks to Hibou's package policy endpoint off the request thread: misses
 * are queued on a bounded executor, batched into one call, single-flighted
 * per purl, and their answers land in the cache for the next request. The
 * optional synchronous path is capped and behind the breaker.
 */
public final class PolicyClient {
    /** Parses the endpoint's JSON into decisions; injected so the core stays JSON-library free. */
    public interface Parser extends Function<String, List<Decision>> {}

    private final String endpoint;
    private final String token;
    private final DecisionCache cache;
    private final CircuitBreaker breaker;
    private final Parser parser;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, Boolean> inflight = new ConcurrentHashMap<>();
    public final AtomicLong lookups = new AtomicLong();
    public final AtomicLong lookupFailures = new AtomicLong();
    public final AtomicLong dropped = new AtomicLong();
    private final Duration syncTimeout;

    public PolicyClient(String hibouUrl, String org, String token, DecisionCache cache, CircuitBreaker breaker, Parser parser, int queueSize, Duration syncTimeout) {
        this.endpoint = hibouUrl.replaceAll("/+$", "") + "/api/v1/orgs/" + org + "/policy/packages";
        this.token = token;
        this.cache = cache;
        this.breaker = breaker;
        this.parser = parser;
        this.syncTimeout = syncTimeout;
        this.executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(Math.max(16, queueSize)),
            r -> {
                Thread thread = new Thread(r, "hibou-plugin-lookup");
                thread.setDaemon(true);
                return thread;
            },
            (r, pool) -> dropped.incrementAndGet());
    }

    /** Queues a lookup for purls not already in flight; returns at once. */
    public void lookupAsync(Collection<String> purls) {
        List<String> mine = new ArrayList<>();
        for (String purl : purls) {
            if (inflight.putIfAbsent(purl, Boolean.TRUE) == null) {
                mine.add(purl);
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                fetch(mine);
            } finally {
                for (String purl : mine) {
                    inflight.remove(purl);
                }
            }
        });
    }

    /** The capped synchronous path: null when the breaker is open or the call exceeds the cap. */
    public Decision lookupSync(String purl, Instant now) {
        if (syncTimeout == null || syncTimeout.isZero() || !breaker.allows(now)) {
            return null;
        }
        Thread caller = Thread.currentThread();
        List<Decision> decisions = fetchWithin(List.of(purl), syncTimeout);
        if (decisions == null) {
            return null;
        }
        for (Decision decision : decisions) {
            if (decision.purl.equals(purl)) {
                return decision;
            }
        }
        return null;
    }

    private List<Decision> fetchWithin(List<String> purls, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        List<Decision> decisions = fetch(purls, (int) Math.min(budget.toMillis(), 250));
        if (System.nanoTime() > deadline) {
            return null;
        }
        return decisions;
    }

    private void fetch(List<String> purls) {
        fetch(purls, 5000);
    }

    private List<Decision> fetch(List<String> purls, int timeoutMillis) {
        Instant now = Instant.now();
        if (!breaker.allows(now)) {
            return null;
        }
        lookups.incrementAndGet();
        try {
            String body = requestBody(purls);
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("policy endpoint returned " + status);
            }
            String response;
            try (InputStream in = connection.getInputStream()) {
                response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            List<Decision> decisions = parser.apply(response);
            for (Decision decision : decisions) {
                cache.put(decision);
            }
            breaker.success();
            return decisions;
        } catch (IOException | RuntimeException e) {
            lookupFailures.incrementAndGet();
            breaker.failure(now);
            return null;
        }
    }

    static String requestBody(List<String> purls) {
        StringBuilder sb = new StringBuilder("{\"purls\":[");
        for (int i = 0; i < purls.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(purls.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("]}").toString();
    }

    public Map<String, Long> metrics() {
        return Map.of(
            "lookups", lookups.get(),
            "lookup_failures", lookupFailures.get(),
            "dropped", dropped.get(),
            "queue_depth", (long) executor.getQueue().size(),
            "inflight", (long) inflight.size(),
            "breaker_open", breaker.isOpen() ? 1L : 0L);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
