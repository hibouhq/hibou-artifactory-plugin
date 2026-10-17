#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Drives the bench: N parallel `npm install` runs through the npm remote,
# then prints the plugin's metrics and the p99 added latency measured by
# comparing cached-artifact downloads with the plugin enabled and disabled
# (hibouReload between the two phases). The numbers to watch, per README
# "Rollout": added p99 on cache hit, zero added latency on miss, flat heap
# over 24 hours (JVM heap sampled from the Artifactory metrics endpoint).
set -euo pipefail
ART=${ART:-http://localhost:8082/artifactory}
AUTH=${ART_AUTH:-admin:password}
RUNS=${RUNS:-20}
mkdir -p target-lib
(cd .. && mvn -q -DskipTests package && cp target/*.jar bench/target-lib/)
phase() {
  local label=$1
  local start=$(date +%s%N)
  for i in $(seq 1 "$RUNS"); do
    (rm -rf /tmp/bench-$i && mkdir -p /tmp/bench-$i && cd /tmp/bench-$i && npm init -y >/dev/null && npm install lodash express --registry "$ART/api/npm/npm-remote/" --loglevel=error) &
  done
  wait
  local end=$(date +%s%N)
  echo "$label: $RUNS installs in $(( (end - start) / 1000000 )) ms"
}
phase "plugin observe"
curl -s -u "$AUTH" "$ART/api/plugins/execute/hibouMetrics"; echo
sed -i 's/^hibou.enabled = .*/hibou.enabled = false/' hibou.properties
curl -s -u "$AUTH" -X POST "$ART/api/plugins/execute/hibouReload"; echo
phase "plugin disabled"
sed -i 's/^hibou.enabled = .*/hibou.enabled = true/' hibou.properties
curl -s -u "$AUTH" -X POST "$ART/api/plugins/execute/hibouReload"; echo
