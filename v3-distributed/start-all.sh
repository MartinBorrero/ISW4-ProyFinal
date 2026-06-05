#!/usr/bin/env bash
set -euo pipefail
WORKERS="${1:-8}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

./gradlew :v3-distributed:partitioner:installDist :v3-distributed:visualization:installDist :v3-distributed:worker:installDist :v3-distributed:master:installDist :v3-distributed:broker:installDist :v3-distributed:client:installDist

test -f "data/partitions-${WORKERS}/partition-0.csv" || { echo "Prepartitioned input not found. Run: bash v3-distributed/prepartition.sh $WORKERS"; exit 1; }

bash v3-distributed/start-visualization.sh 127.0.0.1 &
sleep 2
for i in $(seq 1 "$WORKERS"); do
  port=$((10010 + i))
  bash v3-distributed/start-worker.sh "worker-${i}" "$port" 127.0.0.1 127.0.0.1 127.0.0.1 &
done
sleep 3
bash v3-distributed/start-master.sh 127.0.0.1 127.0.0.1 &
sleep 5
bash v3-distributed/start-broker.sh 127.0.0.1 127.0.0.1 127.0.0.1 &
sleep 3
bash v3-distributed/start-client.sh 127.0.0.1 "$WORKERS" "data/partitions-${WORKERS}"
