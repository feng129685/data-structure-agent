#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(CDPATH= cd -- "$DEPLOY_DIR/.." && pwd)"
TEMPLATE="$DEPLOY_DIR/.env.spring.example"
OUTPUT=""
RELEASE=""

usage() {
  cat <<'EOF'
Usage: init-production-env.sh --output FILE --release RELEASE

Creates a new production environment file outside the repository. Database and
JWT secrets are generated with OpenSSL and are never printed. Existing files
are never overwritten; rotate through the secret manager and an explicit
maintenance procedure instead.
EOF
}

die() { printf '[structify][error] %s\n' "$*" >&2; exit 1; }
log() { printf '[structify] %s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) OUTPUT="${2:-}"; shift 2 ;;
    --release) RELEASE="${2:-}"; shift 2 ;;
    --template) TEMPLATE="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ -n "$OUTPUT" ]] || die "--output is required"
[[ -n "$RELEASE" ]] || die "--release is required"
[[ "$RELEASE" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,100}$ ]] || die "invalid release tag"
[[ "$OUTPUT" == /* ]] || die "--output must be an absolute Linux path"
[[ -r "$TEMPLATE" ]] || die "environment template is not readable"
case "$OUTPUT" in
  "$REPO_DIR"/*) die "production environment must be outside the repository" ;;
esac
command -v openssl >/dev/null 2>&1 || die "required command is missing: openssl"
[[ ! -e "$OUTPUT" && ! -L "$OUTPUT" ]] || die "refusing to overwrite an existing environment file"

parent_dir="$(dirname -- "$OUTPUT")"
if [[ -e "$parent_dir" ]]; then
  [[ -d "$parent_dir" && ! -L "$parent_dir" ]] || die "environment parent must be a real directory"
  parent_mode="$(stat -c '%a' "$parent_dir" 2>/dev/null || true)"
  [[ "$parent_mode" == "700" ]] || die "environment parent must have mode 0700"
else
  mkdir -m 700 -p "$parent_dir"
fi

umask 077
db_password="$(openssl rand -hex 32)"
root_password="$(openssl rand -hex 32)"
jwt_secret="$(openssl rand -hex 32)"
node_compat_jwt_secret="$(openssl rand -hex 32)"
[[ "$jwt_secret" != "$node_compat_jwt_secret" ]] || die "random secret collision; retry"

temporary_file="$(mktemp "$OUTPUT.tmp.XXXXXX")"
cleanup() { rm -f -- "$temporary_file"; }
trap cleanup EXIT

while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    NODE_IMAGE=*) line="NODE_IMAGE=structify-node:$RELEASE" ;;
    SPRING_IMAGE=*) line="SPRING_IMAGE=structify-spring:$RELEASE" ;;
    MYSQL_USER=*) line="MYSQL_USER=structify_app" ;;
    MYSQL_PASSWORD=*) line="MYSQL_PASSWORD=$db_password" ;;
    MYSQL_ROOT_PASSWORD=*) line="MYSQL_ROOT_PASSWORD=$root_password" ;;
    JWT_SECRET=*) line="JWT_SECRET=$jwt_secret" ;;
    NODE_COMPAT_JWT_SECRET=*) line="NODE_COMPAT_JWT_SECRET=$node_compat_jwt_secret" ;;
    HOST_CADDY_CONFIG=*) line="HOST_CADDY_CONFIG=/etc/caddy/Caddyfile" ;;
    CADDY_CONFIG_DIR_HOST=*) line="CADDY_CONFIG_DIR_HOST=/srv/structify/caddy" ;;
    MEMORY_PROFILE=*) line="MEMORY_PROFILE=low-memory" ;;
    MEMORY_BUDGET_MB=*) line="MEMORY_BUDGET_MB=1024" ;;
    MEMORY_RESERVE_MB=*) line="MEMORY_RESERVE_MB=256" ;;
    MIN_AVAILABLE_MEMORY_MB=*) line="MIN_AVAILABLE_MEMORY_MB=1024" ;;
    MYSQL_MEMORY_LIMIT=*) line="MYSQL_MEMORY_LIMIT=384m" ;;
    MYSQL_MEMORY_RESERVATION=*) line="MYSQL_MEMORY_RESERVATION=256m" ;;
    NODE_MEMORY_LIMIT=*) line="NODE_MEMORY_LIMIT=256m" ;;
    NODE_MEMORY_RESERVATION=*) line="NODE_MEMORY_RESERVATION=160m" ;;
    NODE_MAX_OLD_SPACE_MB=*) line="NODE_MAX_OLD_SPACE_MB=160" ;;
    SPRING_MEMORY_LIMIT=*) line="SPRING_MEMORY_LIMIT=384m" ;;
    SPRING_MEMORY_RESERVATION=*) line="SPRING_MEMORY_RESERVATION=288m" ;;
    CADDY_MEMORY_LIMIT=*) line="CADDY_MEMORY_LIMIT=64m" ;;
    CADDY_MEMORY_RESERVATION=*) line="CADDY_MEMORY_RESERVATION=64m" ;;
    AUTH_MAIL_ENABLED=*) line="AUTH_MAIL_ENABLED=false" ;;
    MODEL_API_KEY=*) line="MODEL_API_KEY=" ;;
    PISTON_BASE_URL=*) line="PISTON_BASE_URL=" ;;
    JUDGE0_BASE_URL=*) line="JUDGE0_BASE_URL=" ;;
  esac
  printf '%s\n' "$line" >> "$temporary_file"
done < "$TEMPLATE"

chmod 600 "$temporary_file"
mv -- "$temporary_file" "$OUTPUT"
temporary_file=""
trap - EXIT
chmod 600 "$OUTPUT"
[[ ! -L "$OUTPUT" ]] || die "generated environment file must not be a symlink"
log "production environment created outside the repository for release $RELEASE"
