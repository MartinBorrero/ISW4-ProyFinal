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
  echo "Create it from: cp v3-distributed/deploy.env.example v3-distributed/deploy.env"
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

PARTITIONS="${PARTITIONS:-${#WORKER_HOSTS[@]}}"

case "$ROLE" in
  visualization)
    bash v3-distributed/start-visualization.sh "${VISUALIZATION_HOST:?VISUALIZATION_HOST is required}"
    ;;

  coordination)
    bash v3-distributed/start-master.sh "${MASTER_HOST:?MASTER_HOST is required}" "${VISUALIZATION_HOST:?VISUALIZATION_HOST is required}" &
    sleep 3
    bash v3-distributed/start-broker.sh "${BROKER_HOST:?BROKER_HOST is required}" "$MASTER_HOST" "$VISUALIZATION_HOST"
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
    worker_port=$((10010 + WORKER_INDEX))
    bash v3-distributed/start-worker.sh "worker-${WORKER_INDEX}" "$worker_port" "${MASTER_HOST:?MASTER_HOST is required}" "$worker_host" "${VISUALIZATION_HOST:?VISUALIZATION_HOST is required}"
    ;;

  client)
    client_datagrams_path="${CLIENT_DATAGRAMS_PATH:-data/partitions-${PARTITIONS}}"
    bash v3-distributed/start-client.sh "${BROKER_HOST:?BROKER_HOST is required}" "$PARTITIONS" "$client_datagrams_path"
    ;;

  *)
    echo "Unknown role: $ROLE"
    exit 1
    ;;
esac
