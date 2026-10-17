# Changelog

## 0.1.0-alpha.1 (2026-10-17)

- First alpha: Hibou package policy at the proxy (cooldown holds, malicious-package denies, acceptances): `beforeDownload` backstop on npm, Maven, PyPI, Go, Cargo,
  NuGet and RubyGems remotes; npm packument filtering behind a flag;
  observe and enforce modes; kill switch; reload, status and metrics
  executions; decision cache, circuit breaker and bounded executor per the
  safety budget. Talks to Hibou's policy endpoint v1.
- Alpha: not yet run against an Artifactory. The unit tests cover the Java
  core; the bench and the compatibility test are scripted, their numbers
  are not recorded yet. Observe mode only.
