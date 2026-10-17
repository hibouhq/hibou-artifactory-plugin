// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory

import spock.lang.Specification

import java.time.Duration
import java.time.Instant

/** The safety budget, rule by rule (README "The safety budget"). */
class SafetyBudgetSpec extends Specification {

    def "the decision cache is bounded and evicts by TTL, shorter for negative answers"() {
        given:
        // A long TTL for answers so the hold below is still cached when its cooldown ends;
        // the negative TTL stays short.
        def cache = new DecisionCache(2, Duration.ofDays(30), Duration.ofMinutes(15))
        def t0 = Instant.parse('2026-10-16T00:00:00Z')

        when:
        cache.put(new Decision('pkg:npm/a@1', Decision.Verdict.ALLOW, null, '', true, t0))
        cache.put(new Decision('pkg:npm/b@1', Decision.Verdict.ALLOW, null, '', false, t0))
        cache.put(new Decision('pkg:npm/c@1', Decision.Verdict.HOLD, t0.plus(Duration.ofDays(3)), 'young', true, t0))

        then: 'the oldest entry left when the third came in'
        cache.size() == 2
        cache.evictions.get() == 1
        !cache.get('pkg:npm/a@1', t0).isPresent()

        and: 'a negative answer expires after its own TTL'
        cache.get('pkg:npm/b@1', t0.plus(Duration.ofMinutes(16))).isPresent() == false

        and: 'a hold whose cooldown ended is an allow without a refresh'
        cache.get('pkg:npm/c@1', t0.plus(Duration.ofDays(4))).get().verdict == Decision.Verdict.ALLOW
    }

    def "the breaker opens after consecutive failures and closes after the open period"() {
        given:
        def breaker = new CircuitBreaker(3, Duration.ofMinutes(5))
        def t0 = Instant.parse('2026-10-16T00:00:00Z')

        expect:
        breaker.allows(t0)

        when:
        3.times { breaker.failure(t0) }

        then:
        breaker.isOpen()
        !breaker.allows(t0.plus(Duration.ofMinutes(4)))
        breaker.allows(t0.plus(Duration.ofMinutes(5)))
        !breaker.isOpen()
    }

    def "the pre-filter answers for everything that is not an artifact of an active remote"() {
        given:
        def active = ['npm-remote', 'maven-remote'] as Set

        expect:
        !PreFilter.isArtifact('npm-local', 'lodash/-/lodash-4.17.21.tgz', active)
        !PreFilter.isArtifact('maven-remote', 'org/x/y/1.0/y-1.0.jar.sha1', active)
        !PreFilter.isArtifact('maven-remote', 'org/x/y/maven-metadata.xml', active)
        !PreFilter.isArtifact('npm-remote', 'lodash', active)
        PreFilter.isArtifact('npm-remote', 'lodash/-/lodash-4.17.21.tgz', active)
        PreFilter.isArtifact('maven-remote', 'org/x/y/1.0/y-1.0.jar', active)

        and: 'packument requests are told apart from tarballs'
        PreFilter.isNpmPackument('npm-remote', 'lodash', active)
        PreFilter.isNpmPackument('npm-remote', '@scope/name', active)
        !PreFilter.isNpmPackument('npm-remote', 'lodash/-/lodash-4.17.21.tgz', active)
        !PreFilter.isNpmPackument('npm-remote', '@scope/name/-/name-1.0.0.tgz', active)
    }

    def "paths map to the purls hibou's policy understands"() {
        expect:
        PurlMapper.toPurl('npm', 'lodash/-/lodash-4.17.21.tgz').get() == 'pkg:npm/lodash@4.17.21'
        PurlMapper.toPurl('npm', '@scope/name/-/name-1.0.0.tgz').get() == 'pkg:npm/%40scope/name@1.0.0'
        PurlMapper.toPurl('maven', 'org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar').get() == 'pkg:maven/org.slf4j/slf4j-api@2.0.9'
        PurlMapper.toPurl('pypi', 'packages/ab/cd/Requests-2.31.0-py3-none-any.whl').get() == 'pkg:pypi/requests@2.31.0'
        PurlMapper.toPurl('go', 'github.com/x/y/@v/v1.2.3.zip').get() == 'pkg:golang/github.com/x/y@v1.2.3'
        PurlMapper.toPurl('cargo', 'crates/serde/1.0.0/download').get() == 'pkg:cargo/serde@1.0.0'
        PurlMapper.toPurl('nuget', 'newtonsoft.json.13.0.3.nupkg').get() == 'pkg:nuget/newtonsoft.json@13.0.3'
        !PurlMapper.toPurl('npm', 'lodash').isPresent()
        !PurlMapper.toPurl('docker', 'library/nginx/manifest.json').isPresent()
    }

    def "packument filtering hides young versions and repoints dist-tags, never the whole document"() {
        given:
        def now = Instant.parse('2026-10-16T00:00:00Z')
        def doc = [
            name: 'lodash',
            'dist-tags': [latest: '4.17.22', next: '5.0.0-beta.1'],
            versions: ['4.17.21': [:], '4.17.22': [:], '5.0.0-beta.1': [:]],
            time: ['4.17.21': '2026-01-01T00:00:00Z', '4.17.22': '2026-10-14T00:00:00Z', '5.0.0-beta.1': '2026-10-15T00:00:00Z', modified: '2026-10-15T00:00:00Z'],
        ]

        when:
        def result = PackumentFilter.filter(doc, Duration.ofDays(7), now, [] as Set)

        then:
        result.removed.sort() == ['4.17.22', '5.0.0-beta.1']
        result.document.versions.keySet() == ['4.17.21'] as Set
        result.document['dist-tags'].latest == '4.17.21'
        result.document['dist-tags'].next == '4.17.21'
    }

    def "an exempt version stays in the packument"() {
        given:
        def now = Instant.parse('2026-10-16T00:00:00Z')
        def doc = [versions: ['1.0.0': [:]], time: ['1.0.0': '2026-10-15T00:00:00Z'], 'dist-tags': [latest: '1.0.0']]

        expect:
        PackumentFilter.filter(doc, Duration.ofDays(7), now, ['1.0.0'] as Set).removed.isEmpty()
    }

    def "the request body is the policy endpoint contract"() {
        expect:
        PolicyClient.requestBody(['pkg:npm/a@1', 'pkg:npm/b@2']) == '{"purls":["pkg:npm/a@1","pkg:npm/b@2"]}'
    }

    def "the synchronous lookup is capped at 250 ms whatever the configuration says"() {
        given:
        def props = new Properties()
        props.setProperty('hibou.sync.timeout.ms', '5000')

        expect:
        PluginConfig.of(props).syncTimeout == Duration.ofMillis(250)
    }
}
