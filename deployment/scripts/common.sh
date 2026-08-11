#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(CDPATH= cd -- "$DEPLOY_DIR/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.production.yml}"
ENV_FILE="${ENV_FILE:-/etc/structify/structify.env}"

log() { printf '[structify] %s\n' "$*"; }
warn() { printf '[structify][warn] %s\n' "$*" >&2; }
die() { printf '[structify][error] %s\n' "$*" >&2; exit 1; }

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

print_command() {
  printf '+'
  printf ' %q' "$@"
  printf '\n'
}

confirm_execute() {
  local expected="$1"
  [[ "${EXECUTE:-0}" == "1" ]] || return 1
  [[ "${CONFIRM:-}" == "$expected" ]] || die "mutating action requires --confirm $expected"
}

env_value() {
  local key="$1"
  [[ -r "$ENV_FILE" ]] || die "environment file is not readable: $ENV_FILE"
  awk -F= -v wanted="$key" '$1 == wanted { sub(/^[^=]*=/, ""); print; exit }' "$ENV_FILE"
}

safe_release_tag() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,100}$ ]] || die "invalid release tag: $1"
}

caddy_mode() {
  local value
  value="$(env_value CADDY_MODE)"
  value="${value:-host}"
  [[ "$value" == "host" || "$value" == "container" ]] || die "CADDY_MODE must be host or container"
  printf '%s\n' "$value"
}

loopback_port() {
  local key="$1"
  local fallback="$2"
  local value
  value="$(env_value "$key")"
  value="${value:-$fallback}"
  [[ "$value" =~ ^[0-9]{1,5}$ ]] || die "$key must be a valid TCP port"
  (( 10#$value >= 1024 && 10#$value <= 65535 )) || die "$key must be between 1024 and 65535"
  printf '%s\n' "$value"
}

node_host_port() {
  loopback_port NODE_HOST_PORT 18791
}

spring_host_port() {
  loopback_port SPRING_HOST_PORT 18792
}
