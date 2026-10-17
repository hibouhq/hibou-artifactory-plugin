#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Compatibility test: the plugin's parser against a running Hibou's policy
# endpoint. Starts a Hibou container, seeds it, asks the policy about a
# known set of purls and checks the response shape the plugin reads
# (decisions[].purl, decision, until, reason, evaluated). A plugin release
# states the Hibou versions this passed against (README "Compatibility").
set -euo pipefail
HIBOU_IMAGE=${HIBOU_IMAGE:-ghcr.io/hibouhq/hibou:latest}
docker compose -f "$(dirname "$0")/../bench/docker-compose.yml" up -d hibou hibou-db
trap 'docker compose -f "$(dirname "$0")/../bench/docker-compose.yml" down' EXIT
for i in $(seq 1 60); do
  curl -sf http://localhost:8080/healthz >/dev/null 2>&1 && break
  sleep 2
done
docker compose -f "$(dirname "$0")/../bench/docker-compose.yml" exec -T hibou hibou-server seed >/dev/null
TOKEN=${HIBOU_TOKEN:?set HIBOU_TOKEN to a token of the seeded org github/acme}
RESPONSE=$(curl -sf -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"purls":["pkg:npm/express@4.18.2","pkg:npm/event-stream@3.3.6","pkg:npm/next@14.1.0"]}' \
  http://localhost:8080/api/v1/orgs/github/acme/policy/packages)
echo "$RESPONSE" | python3 -c '
import json, sys
body = json.load(sys.stdin)["data"]
decisions = {d["purl"]: d for d in body["decisions"]}
assert decisions["pkg:npm/event-stream@3.3.6"]["decision"] == "deny", decisions
assert decisions["pkg:npm/next@14.1.0"]["decision"] == "hold" and decisions["pkg:npm/next@14.1.0"]["until"], decisions
assert decisions["pkg:npm/express@4.18.2"]["decision"] == "allow", decisions
for d in body["decisions"]:
    for key in ("purl", "decision", "reason", "evaluated"):
        assert key in d, (key, d)
print("policy endpoint v1: compatible")
'
