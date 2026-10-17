# hibou-artifactory-plugin

Hibou for JFrog Artifactory.

A JFrog Artifactory user plugin that enforces Hibou's package policy at the
registry proxy: a version in its release cooldown, a malicious package, or
anything else the policy denies never reaches a developer machine or a CI
runner; an acceptance recorded in Hibou lets a version through. The
decision comes from the Hibou server's package policy endpoint; the plugin
never decides on its own and never calls the network on a download thread.

This repository is separate from Hibou on purpose: it is Groovy and Java
with a Maven build, released on its own cadence to Artifactory
administrators. Its only contract with Hibou is the policy endpoint,
`POST /api/v1/orgs/{provider}/{slug}/policy/packages`, which is versioned.

## What it does

| Hook | Repositories | Effect |
|---|---|---|
| `altResponse` on npm packument requests | npm remotes listed in the configuration | Drops versions younger than the window from `versions`, `time` and `dist-tags`, so npm, pnpm, Yarn and Bun resolve ranges to a mature version instead of failing. Documents above the size cap are served untouched; the download backstop still applies |
| `beforeDownload` on artifacts | Every remote listed in the configuration (npm, Maven, PyPI, Go, Cargo, NuGet, RubyGems) | The backstop: a version the policy holds (cooldown) or denies (malicious, blocked) is refused with HTTP 403 and a reason naming the Hibou decision. Fires on cached artifacts too |
| Executions `hibouReload`, `hibouStatus`, `hibouMetrics` | — | Reload the configuration without a restart, show the current mode and the decision cache, expose counters |

Decisions come from an in-memory cache. A miss returns the configured
default at once and queues the lookup on a bounded executor; the next
request has the answer. Misses are batched into one policy call and
single-flighted per purl. Negative answers are cached too.

## The safety budget

Every rule below is a test in `src/test` and a line in the bench report. A
user plugin runs in Artifactory's JVM, on the request thread of every
download, with full privileges, so a slow or leaky plugin degrades the
whole instance.

- No network on the request thread. The optional synchronous lookup is
  capped at 250 ms and sits behind a circuit breaker that opens after a few
  failures and stays open for minutes.
- No database write on the hot path. Properties are read once per artifact
  and cached; a block reason is written once per decision change, never per
  request. Exemptions (`hibou.exempt`) are read, never written, by the plugin.
- O(1) pre-filter: repository key and package type are cached per
  repository; local repositories, checksums, metadata files and unknown
  extensions return in a few string operations.
- Bounded memory: the decision cache has a maximum entry count and a TTL;
  filtered packuments are cached by size budget; packuments above the
  configured size are not filtered at all.
- Fail open on internal error. Every hook is wrapped; only a deliberate
  `CancelException` escapes. Any other exception is counted, logged once
  per minute, and the download proceeds.
- Kill switch and observe mode, effective without a restart:
  `hibou.enabled`, `hibou.mode = observe | enforce`, and the `hibouReload`
  execution. Observe mode logs the decision it would have taken.
- Never the artifact's `lastModified` as the package age: it is the cache
  time. The age comes from the Hibou decision, which comes from the
  registry's publish date.
- Enforce never on day one: observe first, with the load test's numbers
  from the instance itself.

## Configuration

`etc/plugins/hibou.properties` (reloaded by `hibouReload`):

```properties
hibou.enabled = true
hibou.mode = observe                 # observe | enforce; start with observe
hibou.url = https://hibou.example.com
hibou.org = github/acme              # provider/slug
hibou.token = <api token with vulnerabilities:read>
hibou.repos = npm-remote, maven-remote, pypi-remote   # remotes the plugin is active on
hibou.default = allow                # decision on a cache miss: allow | hold
hibou.sync.timeout.ms = 0            # > 0 enables the capped synchronous lookup (250 ms max)
hibou.cache.max = 200000
hibou.cache.ttl.minutes = 60
hibou.cache.negative.ttl.minutes = 15
hibou.packument.filter = false       # npm index filtering; enable last, behind this flag
hibou.packument.max.bytes = 2097152
hibou.executor.queue = 10000
hibou.breaker.failures = 5
hibou.breaker.open.minutes = 5
```

## Rollout

1. Observe mode on one npm remote. Watch `hibouMetrics`: added p99 on cache
   hit, zero added latency on miss, flat heap over 24 hours.
2. Enforce on npm.
3. Other package types.
4. Packument filtering, behind its own flag.

## Build and test

```sh
mvn -q test                                      # unit tests: cache, pre-filter, packument filter, breaker, decision rules
docker compose -f bench/docker-compose.yml up    # Artifactory Pro (trial licence) + Hibou, for the load test
./compat/run.sh                                  # compatibility test against a Hibou container
```

The plugin script `src/main/groovy/hibou.groovy` is a thin adapter
over plain Java classes in `src/main/java`, so everything that can go wrong
is unit-tested without an Artifactory.

## Releases

Tags `v*` build the jar and publish it with the script and an example
configuration on the GitHub release; a tag with a pre-release suffix
(`v0.1.0-alpha.1`) is marked pre-release. See `RELEASE-NOTES.md`.

## Compatibility

| Plugin | Hibou policy endpoint | Artifactory |
|---|---|---|
| 0.1.x | v1 (`/api/v1/orgs/{org}/policy/packages`) | 7.x with user plugins enabled |

## License

Apache-2.0 (`LICENSE`, `NOTICE`, SPDX headers on every file). This is
deliberate and separate from Hibou, which stays under the AGPL-3.0: the
plugin runs inside the administrator's Artifactory, includes nothing from
the Hibou code base, and talks to Hibou only through its versioned policy
endpoint.
