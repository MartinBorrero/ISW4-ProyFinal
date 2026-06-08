#!/usr/bin/env bash
set -euo pipefail
BIND_HOST="${1:-127.0.0.1}"
VISUALIZATION_HOST="${2:-127.0.0.1}"
MASTER_PORT="${3:-10001}"
VISUALIZATION_PORT="${4:-10003}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP_CFG="v3-distributed/config/runtime-master.cfg"
mkdir -p "$(dirname "$TMP_CFG")"
cat > "$TMP_CFG" <<CFG
MasterAdapter.Endpoints=tcp -h ${BIND_HOST} -p ${MASTER_PORT}
Visualization.Proxy=Visualization:tcp -h ${VISUALIZATION_HOST} -p ${VISUALIZATION_PORT}
Ice.Warn.Connections=1
Ice.Trace.Network=0
CFG
v3-distributed/master/build/install/master/bin/master --Ice.Config="$TMP_CFG"
