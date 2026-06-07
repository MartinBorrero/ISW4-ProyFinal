#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${1:-v3-distributed/deploy.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing deployment env file: $ENV_FILE"
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

PARTITIONS="${PARTITIONS:?PARTITIONS is required}"
REMOTE_ROOT="${REMOTE_ROOT:?REMOTE_ROOT is required}"
SSH_USER="${SSH_USER:?SSH_USER is required}"

if [[ "${#WORKER_HOSTS[@]}" -lt "$PARTITIONS" ]]; then
  echo "WORKER_HOSTS has ${#WORKER_HOSTS[@]} entries, but PARTITIONS=$PARTITIONS"
  exit 1
fi

test -d "data/partitions-${PARTITIONS}" || {
  echo "Missing data/partitions-${PARTITIONS}. Generate partitions on this machine first."
  exit 1
}

test -f "data/lines-241-ActiveGT.csv" || {
  echo "Missing data/lines-241-ActiveGT.csv on master machine."
  exit 1
}

for i in $(seq 1 "$PARTITIONS"); do
  partition_index=$((i - 1))
  worker_host="${WORKER_HOSTS[$partition_index]}"
  partition_file="data/partitions-${PARTITIONS}/partition-${partition_index}.csv"

  test -f "$partition_file" || {
    echo "Missing $partition_file"
    exit 1
  }

  echo "Sending partition-${partition_index}.csv to worker-${i} at ${worker_host}"
  ssh "${SSH_USER}@${worker_host}" "mkdir -p '${REMOTE_ROOT}/data/partitions-${PARTITIONS}'"
  scp "$partition_file" "${SSH_USER}@${worker_host}:${REMOTE_ROOT}/data/partitions-${PARTITIONS}/"
  scp "data/lines-241-ActiveGT.csv" "${SSH_USER}@${worker_host}:${REMOTE_ROOT}/data/"
done

echo "Partition distribution from master completed."
