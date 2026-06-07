#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${1:-v3-distributed/deploy.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing deployment env file: $ENV_FILE"
  echo "Create it from: cp v3-distributed/deploy.env.example v3-distributed/deploy.env"
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

PARTITIONS="${PARTITIONS:-${#WORKER_HOSTS[@]}}"
MAX_ROWS="${MAX_ROWS:-0}"
REMOTE_ROOT="${REMOTE_ROOT:?REMOTE_ROOT is required}"
SSH_USER="${SSH_USER:?SSH_USER is required}"

if [[ "${#WORKER_HOSTS[@]}" -lt "$PARTITIONS" ]]; then
  echo "WORKER_HOSTS has ${#WORKER_HOSTS[@]} entries, but PARTITIONS=$PARTITIONS"
  exit 1
fi

echo "Building partitioner and creating data/partitions-${PARTITIONS}"
bash v3-distributed/prepartition.sh "$PARTITIONS" "$MAX_ROWS"

if command -v rsync >/dev/null 2>&1; then
  COPY_CMD="rsync"
else
  COPY_CMD="scp"
fi

for i in $(seq 1 "$PARTITIONS"); do
  host="${WORKER_HOSTS[$((i - 1))]}"
  target="${SSH_USER}@${host}:${REMOTE_ROOT}/data/"
  echo "Preparing worker-${i} data on ${host}"
  ssh "${SSH_USER}@${host}" "mkdir -p '${REMOTE_ROOT}/data'"
  if [[ "$COPY_CMD" == "rsync" ]]; then
    rsync -az "data/lines-241-ActiveGT.csv" "data/partitions-${PARTITIONS}" "$target"
  else
    scp "data/lines-241-ActiveGT.csv" "$target"
    ssh "${SSH_USER}@${host}" "rm -rf '${REMOTE_ROOT}/data/partitions-${PARTITIONS}'"
    scp -r "data/partitions-${PARTITIONS}" "$target"
  fi
done

echo "Data distribution completed."
