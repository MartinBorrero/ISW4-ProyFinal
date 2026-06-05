#!/usr/bin/env bash
set -euo pipefail
PARTITIONS="${1:-8}"
MAX_ROWS="${2:-0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./gradlew :v3-distributed:partitioner:installDist
OUTPUT="data/partitions-${PARTITIONS}"
if [[ "$MAX_ROWS" == "0" ]]; then
  v3-distributed/partitioner/build/install/partitioner/bin/partitioner --datagrams data/datagrams4Pilot.csv --routes data/lines-241-ActiveGT.csv --output "$OUTPUT" --partitions "$PARTITIONS"
else
  v3-distributed/partitioner/build/install/partitioner/bin/partitioner --datagrams data/datagrams4Pilot.csv --routes data/lines-241-ActiveGT.csv --output "$OUTPUT" --partitions "$PARTITIONS" --maxRows "$MAX_ROWS"
fi
