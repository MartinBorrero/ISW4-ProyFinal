#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${DEPLOY_ENV:-v3-distributed/deploy.env}"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck source=/dev/null
  source "$ENV_FILE"
fi

if [[ -n "${1:-}" ]]; then
  PARTITIONS="$1"
elif declare -p WORKER_HOSTS >/dev/null 2>&1 && [[ "${#WORKER_HOSTS[@]}" -gt 0 ]]; then
  PARTITIONS="${#WORKER_HOSTS[@]}"
else
  echo "Partition count is required when WORKER_HOSTS is not configured."
  echo "Usage: bash v3-distributed/prepartition.sh <partitions> [maxRows]"
  exit 1
fi

MAX_ROWS="${2:-${MAX_ROWS:-0}}"
./gradlew :v3-distributed:partitioner:installDist
OUTPUT="data/partitions-${PARTITIONS}"
if [[ "$MAX_ROWS" == "0" ]]; then
  v3-distributed/partitioner/build/install/partitioner/bin/partitioner --datagrams data/datagrams4Pilot.csv --routes data/lines-241-ActiveGT.csv --output "$OUTPUT" --partitions "$PARTITIONS"
else
  v3-distributed/partitioner/build/install/partitioner/bin/partitioner --datagrams data/datagrams4Pilot.csv --routes data/lines-241-ActiveGT.csv --output "$OUTPUT" --partitions "$PARTITIONS" --maxRows "$MAX_ROWS"
fi
