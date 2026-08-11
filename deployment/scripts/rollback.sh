#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

RELEASE_ENV=""
EXECUTE=0
CONFIRM=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-env) RELEASE_ENV="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    --confirm) CONFIRM="$2"; shift 2 ;;
    -h|--help) printf '%s\n' 'Usage: rollback.sh --release-env FILE [--execute --confirm ROLLBACK-structify.cn]'; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done
[[ -r "$RELEASE_ENV" ]] || die "--release-env must point to a release image manifest"
node_image="$(awk -F= '$1 == "NODE_IMAGE" {print substr($0, index($0, "=") + 1); exit}' "$RELEASE_ENV")"
spring_image="$(awk -F= '$1 == "SPRING_IMAGE" {print substr($0, index($0, "=") + 1); exit}' "$RELEASE_ENV")"
node_image_id="$(awk -F= '$1 == "NODE_IMAGE_ID" {print substr($0, index($0, "=") + 1); exit}' "$RELEASE_ENV")"
spring_image_id="$(awk -F= '$1 == "SPRING_IMAGE_ID" {print substr($0, index($0, "=") + 1); exit}' "$RELEASE_ENV")"
[[ -n "$node_image" && -n "$spring_image" ]] || die "release manifest is missing image names"
[[ "$node_image_id" =~ ^sha256:[a-f0-9]{64}$ && "$spring_image_id" =~ ^sha256:[a-f0-9]{64}$ ]] || die "release manifest is missing immutable image IDs"
[[ "$node_image" != *[[:space:]]* && "$spring_image" != *[[:space:]]* ]] || die "release manifest contains whitespace"
[[ "$node_image" =~ ^[A-Za-z0-9._/-]+(:[A-Za-z0-9._-]+|@sha256:[a-f0-9]{64})$ ]] || die "invalid Node image reference"
[[ "$spring_image" =~ ^[A-Za-z0-9._/-]+(:[A-Za-z0-9._-]+|@sha256:[a-f0-9]{64})$ ]] || die "invalid Spring image reference"

if [[ "$EXECUTE" != "1" ]]; then
  log "dry-run application rollback plan"
  printf '+ NODE_IMAGE=%q SPRING_IMAGE=%q ' "$node_image" "$spring_image"
  printf 'docker compose --env-file %q -f %q up -d --no-deps node spring-api\n' "$ENV_FILE" "$COMPOSE_FILE"
  log "database rollback is a separate restore operation and is never implicit"
  exit 0
fi
[[ "$CONFIRM" == "ROLLBACK-structify.cn" ]] || die "rollback requires --confirm ROLLBACK-structify.cn"
require_command docker
compose config --quiet
[[ "$(docker image inspect --format '{{.Id}}' "$node_image")" == "$node_image_id" ]] || die "Node tag no longer resolves to the recorded image ID"
[[ "$(docker image inspect --format '{{.Id}}' "$spring_image")" == "$spring_image_id" ]] || die "Spring tag no longer resolves to the recorded image ID"
NODE_IMAGE="$node_image" SPRING_IMAGE="$spring_image" \
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-deps node spring-api
"$SCRIPT_DIR/health-check.sh" --env-file "$ENV_FILE" --execute
log "application rollback complete; verify Flyway compatibility before serving traffic"
