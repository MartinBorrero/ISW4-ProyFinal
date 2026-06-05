#!/usr/bin/env bash
set -euo pipefail
BROKER_HOST="${1:-127.0.0.1}"
PARTITIONS="${2:-8}"
PARTITION_DIR="${3:-data/partitions-${PARTITIONS}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP_CFG="v3-distributed/config/runtime-client-${PARTITIONS}.cfg"
cat > "$TMP_CFG" <<CFG
Broker.Proxy=Broker:tcp -h ${BROKER_HOST} -p 10000
Client.TaskId=v3-flex-demo
Client.DatagramsPath=${PARTITION_DIR}
Client.RoutesPath=data/lines-241-ActiveGT.csv
Client.OutputPath=output/resultados-v3.csv
Client.MaxRows=0
Client.PartitionCount=${PARTITIONS}
Ice.Warn.Connections=1
Ice.Trace.Network=0
CFG
v3-distributed/client/build/install/client/bin/client --Ice.Config="$TMP_CFG"
