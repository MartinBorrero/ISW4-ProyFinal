#!/usr/bin/env bash
set -euo pipefail
WORKER_ID="${1:-worker-1}"
PORT="${2:-10011}"
MASTER_HOST="${3:-127.0.0.1}"
BIND_HOST="${4:-127.0.0.1}"
VISUALIZATION_HOST="${5:-127.0.0.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP_CFG="v3-distributed/config/runtime-${WORKER_ID}.cfg"
cat > "$TMP_CFG" <<CFG
Worker.Id=${WORKER_ID}
WorkerAdapter.Endpoints=tcp -h ${BIND_HOST} -p ${PORT}
Master.Proxy=Master:tcp -h ${MASTER_HOST} -p 10001
Visualization.Proxy=Visualization:tcp -h ${VISUALIZATION_HOST} -p 10003
Ice.Warn.Connections=1
Ice.Trace.Network=0
CFG
v3-distributed/worker/build/install/worker/bin/worker --Ice.Config="$TMP_CFG"
