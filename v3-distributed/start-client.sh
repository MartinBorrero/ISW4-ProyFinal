#!/usr/bin/env bash
set -euo pipefail
BROKER_HOST="${1:-127.0.0.1}"
PARTITIONS="${2:-8}"
PARTITION_DIR="${3:-data/partitions-${PARTITIONS}}"
BROKER_PORT="${4:-10000}"
MAX_ROWS="${5:-0}"
OUTPUT_PATH="${6:-output/resultados-v3.csv}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP_CFG="v3-distributed/config/runtime-client-${PARTITIONS}.cfg"
mkdir -p "$(dirname "$TMP_CFG")"
cat > "$TMP_CFG" <<CFG
Broker.Proxy=Broker:tcp -h ${BROKER_HOST} -p ${BROKER_PORT}
Client.TaskId=v3-flex-demo
Client.DatagramsPath=${PARTITION_DIR}
Client.RoutesPath=data/lines-241-ActiveGT.csv
Client.OutputPath=${OUTPUT_PATH}
Client.MaxRows=${MAX_ROWS}
Client.PartitionCount=${PARTITIONS}
Ice.Warn.Connections=1
Ice.Trace.Network=0
CFG
bash v3-distributed/client/build/install/client/bin/client --Ice.Config="$TMP_CFG"
