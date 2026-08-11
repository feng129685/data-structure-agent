#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

EXECUTE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    -h|--help) printf '%s\n' 'Usage: health-check.sh [--env-file FILE] [--execute]'; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

node_port="$(node_host_port)"
spring_port="$(spring_host_port)"

commands=(
  "curl --fail --silent --show-error --max-time 10 http://127.0.0.1:$node_port/healthz"
  "curl --fail --silent --show-error --max-time 10 http://127.0.0.1:$spring_port/actuator/health"
  "docker compose --env-file $ENV_FILE -f $COMPOSE_FILE ps"
)
if [[ "$EXECUTE" != "1" ]]; then
  log "dry-run health checks"
  for command in "${commands[@]}"; do printf '+ %s\n' "$command"; done
  exit 0
fi

require_command curl
require_command docker

wait_for_loopback_health() {
  local service="$1"
  local url="$2"
  for attempt in $(seq 1 30); do
    if curl --fail --silent --show-error --max-time 10 "$url" >/dev/null 2>&1; then
      return 0
    fi
    [[ "$attempt" -lt 30 ]] || die "$service did not become healthy on loopback"
    sleep 2
  done
}

wait_for_loopback_health "Node" "http://127.0.0.1:$node_port/healthz"
wait_for_loopback_health "Spring" "http://127.0.0.1:$spring_port/actuator/health"
compose ps
log "loopback health checks passed"
