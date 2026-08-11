#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
SQLITE="${REPO_DIR}/private/state/node/data.db"
TARGET="staging"
OUTPUT=""
EXECUTE=0
CONFIRM=""

usage() {
  cat <<'EOF'
Usage: migrate-sqlite.sh [--sqlite FILE] [--target staging] [--emit-sql FILE]
                         [--execute --confirm MIGRATE-staging]

Without --emit-sql this runs the repository importer in read-only audit mode.
The importer itself rejects production targets. SQL generation is still a file
write and requires an explicit confirmation after a restore-tested backup.
This wrapper never connects to MySQL.
EOF
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --sqlite) SQLITE="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --emit-sql) OUTPUT="$2"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    --confirm) CONFIRM="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

command -v node >/dev/null 2>&1 || { printf '%s\n' 'node is required' >&2; exit 1; }
args=("$REPO_DIR/scripts/sqlite-to-mysql-import.js" --sqlite "$SQLITE" --target "$TARGET")
if [[ -n "$OUTPUT" ]]; then
  [[ "$EXECUTE" == "1" ]] || { printf '%s\n' 'SQL generation is disabled in dry-run; add --execute.' >&2; exit 2; }
  [[ "$CONFIRM" == 'MIGRATE-staging' ]] || { printf '%s\n' 'use --confirm MIGRATE-staging for a new staging SQL file' >&2; exit 2; }
  args+=(--emit-sql "$OUTPUT" --backup-confirmed)
fi
printf '+ node'
printf ' %q' "${args[@]}"
printf '\n'
if [[ "$EXECUTE" == "1" || -z "$OUTPUT" ]]; then
  node "${args[@]}"
fi
