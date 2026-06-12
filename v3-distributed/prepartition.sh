#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${DEPLOY_ENV:-v3-distributed/deploy.env}"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck source=/dev/null
  source "$ENV_FILE"
fi

DATAGRAMS_PATH="${DATAGRAMS_PATH:-data/datagrams4Pilot.csv}"
ROUTES_PATH="${ROUTES_PATH:-data/lines-241-ActiveGT.csv}"
OUTPUT_DIR=""
PARTITIONS=""
MAX_ROWS="${MAX_ROWS:-0}"

usage() {
  echo "Usage:"
  echo "  bash v3-distributed/prepartition.sh [--datagrams path] [--routes path] [--output dir] [--partitions n] [--maxRows n]"
  echo "  bash v3-distributed/prepartition.sh <partitions> [maxRows]"
}

if [[ "${1:-}" =~ ^[0-9]+$ ]]; then
  PARTITIONS="$1"
  MAX_ROWS="${2:-$MAX_ROWS}"
  shift
  if [[ $# -gt 0 ]]; then
    shift
  fi
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --datagrams)
      DATAGRAMS_PATH="${2:?--datagrams requires a path}"
      shift 2
      ;;
    --routes)
      ROUTES_PATH="${2:?--routes requires a path}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="${2:?--output requires a directory}"
      shift 2
      ;;
    --partitions)
      PARTITIONS="${2:?--partitions requires a number}"
      shift 2
      ;;
    --maxRows)
      MAX_ROWS="${2:?--maxRows requires a number}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$PARTITIONS" ]]; then
  if declare -p WORKER_HOSTS >/dev/null 2>&1 && [[ "${#WORKER_HOSTS[@]}" -gt 0 ]]; then
    PARTITIONS="${#WORKER_HOSTS[@]}"
  else
    echo "Partition count is required when WORKER_HOSTS is not configured."
    echo "Use --partitions n or configure WORKER_HOSTS in v3-distributed/deploy.env."
    exit 1
  fi
fi

if ! [[ "$PARTITIONS" =~ ^[0-9]+$ ]] || [[ "$PARTITIONS" -lt 1 ]]; then
  echo "Invalid partition count: $PARTITIONS"
  exit 1
fi

if ! [[ "$MAX_ROWS" =~ ^[0-9]+$ ]]; then
  echo "Invalid maxRows value: $MAX_ROWS"
  exit 1
fi

if [[ -z "$OUTPUT_DIR" ]]; then
  OUTPUT_DIR="data/partitions-${PARTITIONS}"
fi

PARTITIONER="v3-distributed/partitioner/build/install/partitioner/bin/partitioner"

if [[ ! -x "$PARTITIONER" ]]; then
  if [[ -x "./gradlew" ]]; then
    ./gradlew :v3-distributed:partitioner:installDist
  else
    echo "Missing executable partitioner: $PARTITIONER"
    echo "Copy the compiled partitioner build to this node or run Gradle on the development machine."
    exit 1
  fi
fi

"$PARTITIONER" \
  --datagrams "$DATAGRAMS_PATH" \
  --routes "$ROUTES_PATH" \
  --output "$OUTPUT_DIR" \
  --partitions "$PARTITIONS" \
  --maxRows "$MAX_ROWS"
