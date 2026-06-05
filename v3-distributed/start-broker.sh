#!/usr/bin/env bash
set -euo pipefail
BIND_HOST="${1:-127.0.0.1}"
MASTER_HOST="${2:-127.0.0.1}"
VISUALIZATION_HOST="${3:-127.0.0.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
v3-distributed/broker/build/install/broker/bin/broker --Ice.Config=v3-distributed/config/broker.cfg --BrokerAdapter.Endpoints="tcp -h $BIND_HOST -p 10000" --Master.Proxy="Master:tcp -h $MASTER_HOST -p 10001" --Visualization.Proxy="Visualization:tcp -h $VISUALIZATION_HOST -p 10003"
