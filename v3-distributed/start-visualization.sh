#!/usr/bin/env bash
set -euo pipefail
BIND_HOST="${1:-127.0.0.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
v3-distributed/visualization/build/install/visualization/bin/visualization --Ice.Config=v3-distributed/config/visualization.cfg --VisualizationAdapter.Endpoints="tcp -h $BIND_HOST -p 10003"
