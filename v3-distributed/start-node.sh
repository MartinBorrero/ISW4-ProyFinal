#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ROLE="${1:-}"
WORKER_INDEX="${2:-}"
ENV_FILE="${DEPLOY_ENV:-v3-distributed/deploy.env}"

if [[ -z "$ROLE" ]]; then
  echo "Usage:"
  echo "  bash v3-distributed/start-node.sh visualization"
  echo "  bash v3-distributed/start-node.sh coordination"
  echo "  bash v3-distributed/start-node.sh worker <index>"
  echo "  bash v3-distributed/start-node.sh client"
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing deployment env file: $ENV_FILE"
  echo "Create v3-distributed/deploy.env with the IPs and remote path for the lab."
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

PARTITIONS="${PARTITIONS:-${#WORKER_HOSTS[@]}}"
BROKER_PORT="${BROKER_PORT:-10000}"
MASTER_PORT="${MASTER_PORT:-10001}"
VISUALIZATION_PORT="${VISUALIZATION_PORT:-10003}"
WORKER_BASE_PORT="${WORKER_BASE_PORT:-10010}"
MAX_ROWS="${MAX_ROWS:-0}"
WORKER_HEAP="${WORKER_HEAP:-1024m}"

case "$ROLE" in
  visualization)
    bash v3-distributed/start-visualization.sh "${VISUALIZATION_HOST:?VISUALIZATION_HOST is required}" "$VISUALIZATION_PORT"
    ;;

  coordination)
    bash v3-distributed/start-master.sh "${MASTER_HOST:?MASTER_HOST is required}" "${VISUALIZATION_HOST:?VISUALIZATION_HOST is required}" "$MASTER_PORT" "$VISUALIZATION_PORT" &
    master_pid=$!
    trap 'kill "$master_pid" 2>/dev/null || true' EXIT INT TERM
    sleep 3
    bash v3-distributed/start-broker.sh "${BROKER_HOST:?BROKER_HOST is required}" "$MASTER_HOST" "$VISUALIZATION_HOST" "$BROKER_PORT" "$MASTER_PORT" "$VISUALIZATION_PORT"
    ;;

  worker)
    if [[ -z "$WORKER_INDEX" ]]; then
      echo "Worker index is required. Example: bash v3-distributed/start-node.sh worker 4"
      exit 1
    fi
    if ! [[ "$WORKER_INDEX" =~ ^[0-9]+$ ]] || [[ "$WORKER_INDEX" -lt 1 ]] || [[ "$WORKER_INDEX" -gt "${#WORKER_HOSTS[@]}" ]]; then
      echo "Invalid worker index: $WORKER_INDEX"
      exit 1
    fi
    worker_host="${WORKER_HOSTS[$((WORKER_INDEX - 1))]}"
    worker_port=$((WORKER_BASE_PORT + WORKER_INDEX))
    bash v3-distributed/start-worker.sh "worker-${WORKER_INDEX}" "$worker_port" "${MASTER_HOST:?MASTER_HOST is required}" "$worker_host" "${VISUALIZATION_HOST:?VISUALIZATION_HOST is required}" "$MASTER_PORT" "$VISUALIZATION_PORT" "$WORKER_HEAP"
    ;;

  client)
    client_datagrams_path="${CLIENT_DATAGRAMS_PATH:-data/partitions-${PARTITIONS}}"
    bash v3-distributed/start-client.sh "${BROKER_HOST:?BROKER_HOST is required}" "$PARTITIONS" "$client_datagrams_path" "$BROKER_PORT" "$MAX_ROWS"
    ;;

  *)
    echo "Unknown role: $ROLE"
    exit 1
    ;;
esac
