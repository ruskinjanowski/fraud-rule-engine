#!/usr/bin/env bash
# Runs the k6 read-path load test in a throwaway container (no host k6 needed).
# Assumes the app is reachable on the host at :8080.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -e BASE_URL="${BASE_URL:-http://host.docker.internal:8080}" \
  -e CUSTOMERS="${CUSTOMERS:-2000}" \
  -v "$ROOT/load":/load \
  grafana/k6 run /load/read-load.js
