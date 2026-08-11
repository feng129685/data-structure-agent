#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

BACKUP_DIR=""
EXECUTE=0
CONFIRM=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup-dir) BACKUP_DIR="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    --confirm) CONFIRM="$2"; shift 2 ;;
    -h|--help) printf '%s\n' 'Usage: restore.sh --backup-dir DIR [--execute --confirm RESTORE-structify.cn]'; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done
[[ -n "$BACKUP_DIR" && -d "$BACKUP_DIR" ]] || die "--backup-dir must be an existing backup directory"
[[ -r "$BACKUP_DIR/SHA256SUMS" ]] || die "backup is missing SHA256SUMS"
[[ -r "$BACKUP_DIR/mysql.sql" && -r "$BACKUP_DIR/node.sqlite" ]] || die "backup is missing database artifacts"

if [[ "$EXECUTE" != "1" ]]; then
  caddy_mode_value="$(caddy_mode)"
  log "dry-run restore plan for $BACKUP_DIR"
  print_command '(cd BACKUP_DIR && sha256sum -c SHA256SUMS)'
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" stop node spring-api
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d mysql
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T mysql mysql restore from mysql.sql
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" run --rm --no-deps -T node restore node.sqlite through stdin into /app/data
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d node spring-api
  if [[ "$caddy_mode_value" == "container" ]]; then
    print_command docker compose --profile container-caddy --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d caddy
  else
    log "host Caddy mode: reload the existing host configuration only after application health passes"
  fi
  log "database restore replaces current state and requires --execute --confirm RESTORE-structify.cn"
  exit 0
fi

[[ "$CONFIRM" == "RESTORE-structify.cn" ]] || die "restore requires --confirm RESTORE-structify.cn"
require_command docker
(cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS)
compose stop node spring-api
compose up -d mysql
for attempt in $(seq 1 30); do
  if compose exec -T mysql mysqladmin ping -h 127.0.0.1 --silent >/dev/null 2>&1; then
    break
  fi
  [[ "$attempt" -lt 30 ]] || die "MySQL did not become healthy for restore; inspect compose logs"
  sleep 2
done
compose exec -T mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' < "$BACKUP_DIR/mysql.sql"
compose run --rm --no-deps -T --entrypoint /bin/sh node -c 'set -eu; target=/app/data/data.db; temporary="${target}.restore"; rm -f "$temporary"; cat > "$temporary"; mv "$temporary" "$target"; rm -f /app/data/data.db-wal /app/data/data.db-shm' < "$BACKUP_DIR/node.sqlite"
if [[ -r "$BACKUP_DIR/node-pdfs.tar.gz" ]]; then
  compose run --rm --no-deps -T --entrypoint /bin/sh node -c 'set -eu; stage="$(mktemp -d)"; trap "rm -rf \"$stage\"" EXIT; tar -xzf - -C "$stage"; rm -rf /app/pdfs/* /app/pdfs/.[!.]*; cp -a "$stage"/. /app/pdfs/' < "$BACKUP_DIR/node-pdfs.tar.gz"
fi
compose up -d node spring-api
if [[ "$(caddy_mode)" == "container" ]]; then
  docker compose --profile container-caddy --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d caddy
fi
for attempt in $(seq 1 30); do
  if "$SCRIPT_DIR/health-check.sh" --env-file "$ENV_FILE" --execute >/dev/null 2>&1; then
    break
  fi
  [[ "$attempt" -lt 30 ]] || die "restored services did not become healthy; inspect compose logs"
  sleep 2
done
log "database restore complete; private media must be restored from its independent snapshot"
