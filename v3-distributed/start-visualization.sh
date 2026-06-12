#!/usr/bin/env bash
set -euo pipefail
BIND_HOST="${1:-127.0.0.1}"
VISUALIZATION_PORT="${2:-10003}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP_CFG="v3-distributed/config/runtime-visualization.cfg"
mkdir -p "$(dirname "$TMP_CFG")"
cat > "$TMP_CFG" <<CFG
VisualizationAdapter.Endpoints=tcp -h ${BIND_HOST} -p ${VISUALIZATION_PORT}
Ice.Warn.Connections=1
Ice.Trace.Network=0
CFG
bash v3-distributed/visualization/build/install/visualization/bin/visualization --Ice.Config="$TMP_CFG"
