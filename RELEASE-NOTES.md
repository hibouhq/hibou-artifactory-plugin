# v0.1.0-alpha.1

First alpha of the Hibou Artifactory plugin: Hibou's package policy (release
cooldown, malicious packages, acceptances) enforced at the registry proxy with a `beforeDownload` backstop on npm, Maven, PyPI, Go, Cargo, NuGet and
RubyGems remotes, and npm packument filtering behind its own flag.

**Alpha means:** the Java core is unit-tested (decision cache, circuit
breaker, pre-filter, purl mapping, packument filter, configuration caps),
but the plugin has not run inside an Artifactory yet. The bench numbers the
README asks for before enforcing (added p99 on cache hit, zero added
latency on miss, flat heap over 24 hours) are not recorded. Install it in
**observe mode on one npm remote**, read `hibouMetrics`, and do not switch
`hibou.mode = enforce` until the numbers are yours.

Install: copy `hibou.groovy` to `etc/artifactory/plugins/`, the jar to
`etc/artifactory/plugins/lib/`, and `hibou.properties.example` to
`etc/artifactory/plugins/hibou.properties` with your Hibou URL, organization,
token and the remotes to watch. `POST /api/plugins/execute/hibouReload`
applies changes without a restart.

Talks to Hibou's policy endpoint v1 (`/api/v1/orgs/{org}/policy/packages`).
