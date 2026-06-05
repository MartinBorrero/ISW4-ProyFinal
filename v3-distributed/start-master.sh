#!/usr/bin/env bash
set -euo pipefail
BIND_HOST="${1:-127.0.0.1}"
VISUALIZATION_HOST="${2:-127.0.0.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
v3-distributed/master/build/install/master/bin/master --Ice.Config=v3-distributed/config/master.cfg --MasterAdapter.Endpoints="tcp -h $BIND_HOST -p 10001" --Visualization.Proxy="Visualization:tcp -h $VISUALIZATION_HOST -p 10003"
