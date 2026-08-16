#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() {
  cat <<'EOF'
Usage: preflight.sh [--env-file FILE] [--compose-file FILE] [--execute]

Read-only by default. --execute additionally requires the private host paths
to exist and checks that Docker can render the production Compose model.
EOF
}

validate_origin_ca_caddy() {
  local origin_cert_dir="$1"
  local caddy_image
  caddy_image="$(env_value CADDY_IMAGE)"
  caddy_image="${caddy_image:-caddy:2.10-alpine}"
  [[ "$caddy_image" != __*__ ]] || die "CADDY_IMAGE still contains a placeholder"

  # Do not allow preflight to pull a mutable image. The locally available image
  # must be the exact one Compose will run, so certificate parsing cannot defer
  # until the public Caddy container is recreated.
  docker image inspect "$caddy_image" >/dev/null 2>&1 \
    || die "CADDY_IMAGE is not available locally for Origin CA validation"
  docker run --rm --network none --read-only --user 0:0 \
    --cap-drop ALL --cap-add NET_BIND_SERVICE --security-opt no-new-privileges:true \
    --tmpfs /tmp:rw,nosuid,nodev,size=16m \
    --tmpfs /config:rw,nosuid,nodev,size=16m \
    --tmpfs /data:rw,nosuid,nodev,size=16m \
    --mount "type=bind,src=$DEPLOY_DIR/Caddyfile.production,dst=/etc/caddy/Caddyfile,readonly" \
    --mount "type=bind,src=$origin_cert_dir,dst=/etc/caddy/origin-ca,readonly" \
    --env 'CADDY_EMAIL_DIRECTIVE=' \
    --env 'CADDY_TLS_DIRECTIVE=tls /etc/caddy/origin-ca/origin.crt /etc/caddy/origin-ca/origin.key' \
    "$caddy_image" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile \
    >/dev/null 2>&1 \
    || die "container Caddy Origin CA configuration validation failed"
  log "container Caddy Origin CA configuration validated"
}

running_compose_caddy() {
  local expected_project
  local caddy_container
  local caddy_project
  local caddy_service
  local caddy_running
  local -a caddy_containers=()

  expected_project="$(env_value COMPOSE_PROJECT_NAME)"
  expected_project="${expected_project:-structify}"
  mapfile -t caddy_containers < <(compose ps -q caddy 2>/dev/null || true)
  (( ${#caddy_containers[@]} == 1 )) || return 1
  caddy_container="${caddy_containers[0]}"
  [[ -n "$caddy_container" ]] || return 1

  caddy_running="$(docker inspect --format '{{.State.Running}}' "$caddy_container" 2>/dev/null || true)"
  [[ "$caddy_running" == "true" ]] || return 1
  caddy_project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$caddy_container" 2>/dev/null || true)"
  [[ "$caddy_project" == "$expected_project" ]] || return 1
  caddy_service="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$caddy_container" 2>/dev/null || true)"
  [[ "$caddy_service" == "caddy" ]] || return 1

  printf '%s\n' "$caddy_container"
}

caddy_binds_public_tcp_port() {
  local caddy_container="$1"
  local public_port="$2"
  local binding

  while IFS= read -r binding; do
    case "$binding" in
      "0.0.0.0:$public_port"|"[::]:$public_port"|":::$public_port") return 0 ;;
    esac
  done < <(docker port "$caddy_container" "$public_port/tcp" 2>/dev/null || true)
  return 1
}

EXECUTE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --compose-file) COMPOSE_FILE="$2"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ -r "$ENV_FILE" ]] || die "environment file is not readable: $ENV_FILE"
if [[ "$EXECUTE" == "1" ]]; then
  [[ ! -L "$ENV_FILE" ]] || die "environment file must not be a symlink: $ENV_FILE"
  env_mode="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || true)"
  [[ "$env_mode" == "600" ]] || die "environment file must have mode 0600 (got ${env_mode:-unknown})"
fi
mode="$(caddy_mode)"
node_port="$(node_host_port)"
spring_port="$(spring_host_port)"
[[ "$node_port" != "$spring_port" ]] || die "NODE_HOST_PORT and SPRING_HOST_PORT must differ"

required=(MYSQL_DATABASE MYSQL_USER MYSQL_PASSWORD MYSQL_ROOT_PASSWORD JWT_SECRET NODE_COMPAT_JWT_SECRET KNOWLEDGE_DIR_HOST RESOURCE_DIR_HOST PRESENTATION_DIR_HOST PDF_SOURCE_DIR_HOST NODE_IMAGE SPRING_IMAGE)
for key in "${required[@]}"; do
  value="$(env_value "$key")"
  [[ -n "$value" ]] || die "$key is empty"
  [[ "$value" != __*__ ]] || die "$key still contains a placeholder"
done

# Model, mail, and remote execution are runtime integrations. The application
# may start without them and report a precise unavailable capability; when an
# integration is enabled, all of its required fields must be present.
model_key="$(env_value MODEL_API_KEY)"
if [[ -n "$model_key" ]]; then
  for key in MODEL_PROVIDER MODEL_BASE_URL MODEL_NAME; do
    value="$(env_value "$key")"
    [[ -n "$value" ]] || die "$key is required when MODEL_API_KEY is configured"
    [[ "$value" != __*__ ]] || die "$key still contains a placeholder"
  done
else
  log "model integration disabled (MODEL_API_KEY is empty)"
fi

if [[ "$mode" == "host" ]]; then
  [[ -f "$DEPLOY_DIR/Caddyfile.host.production" ]] || die "host Caddy site block is missing"
  log "host Caddy mode: Structify will not bind public 80/443"
  if [[ "$EXECUTE" == "1" ]]; then
    host_caddy_config="$(env_value HOST_CADDY_CONFIG)"
    [[ -n "$host_caddy_config" ]] || die "HOST_CADDY_CONFIG is required in host Caddy mode"
    [[ "$host_caddy_config" == /* ]] || die "HOST_CADDY_CONFIG must be an absolute Linux path"
    [[ -r "$host_caddy_config" ]] || die "HOST_CADDY_CONFIG is not readable: $host_caddy_config"
    require_command caddy
    caddy validate --config "$host_caddy_config" --adapter caddyfile >/dev/null \
      || die "host Caddy configuration validation failed"
    log "host Caddy configuration validated"
  fi
else
  caddy_config_dir="$(env_value CADDY_CONFIG_DIR_HOST)"
  [[ -n "$caddy_config_dir" ]] || caddy_config_dir="/srv/structify/caddy"
  [[ "$caddy_config_dir" == /* ]] || die "CADDY_CONFIG_DIR_HOST must be an absolute Linux path"
  caddy_config_dir_real="$(realpath -m "$caddy_config_dir" 2>/dev/null || true)"
  deploy_dir_real="$(realpath -m "$DEPLOY_DIR" 2>/dev/null || true)"
  [[ -n "$caddy_config_dir_real" && -n "$deploy_dir_real" ]] \
    || die "cannot resolve CADDY_CONFIG_DIR_HOST outside the release directory"
  [[ "$caddy_config_dir_real" != "$deploy_dir_real" && "$caddy_config_dir_real" != "$deploy_dir_real/"* ]] \
    || die "CADDY_CONFIG_DIR_HOST must be outside the release directory"
  if [[ "$EXECUTE" == "1" && -e "$caddy_config_dir" ]]; then
    [[ -d "$caddy_config_dir" && ! -L "$caddy_config_dir" ]] \
      || die "CADDY_CONFIG_DIR_HOST must be a real directory: $caddy_config_dir"
  fi

  origin_cert_dir="$(env_value ORIGIN_CERT_DIR_HOST)"
  if [[ -n "$origin_cert_dir" ]]; then
    [[ "$origin_cert_dir" == /* ]] || die "ORIGIN_CERT_DIR_HOST must be an absolute Linux path"
    if [[ "$EXECUTE" == "1" ]]; then
      [[ -d "$origin_cert_dir" ]] || die "ORIGIN_CERT_DIR_HOST does not exist: $origin_cert_dir"
      [[ ! -L "$origin_cert_dir" ]] || die "ORIGIN_CERT_DIR_HOST must not be a symlink"
      origin_dir_mode="$(stat -c '%a' "$origin_cert_dir" 2>/dev/null || true)"
      [[ "$origin_dir_mode" == "700" ]] \
        || die "ORIGIN_CERT_DIR_HOST must have mode 0700 (got ${origin_dir_mode:-unknown})"
      for origin_file in origin.crt origin.key; do
        [[ -f "$origin_cert_dir/$origin_file" ]] || die "ORIGIN_CERT_DIR_HOST must contain $origin_file"
        [[ ! -L "$origin_cert_dir/$origin_file" ]] || die "ORIGIN_CERT_DIR_HOST/$origin_file must not be a symlink"
        [[ -r "$origin_cert_dir/$origin_file" ]] || die "ORIGIN_CERT_DIR_HOST/$origin_file is not readable"
      done
      origin_key_mode="$(stat -c '%a' "$origin_cert_dir/origin.key" 2>/dev/null || true)"
      [[ "$origin_key_mode" == "600" ]] \
        || die "ORIGIN_CERT_DIR_HOST/origin.key must have mode 0600 (got ${origin_key_mode:-unknown})"
    fi
    log "container Caddy mode: using operator-managed Origin CA certificate"
  else
    acme_email="$(env_value ACME_EMAIL)"
    [[ -n "$acme_email" && "$acme_email" != __*__ ]] || die "ACME_EMAIL is required when ORIGIN_CERT_DIR_HOST is empty"
    log "container Caddy mode: using ACME"
  fi
  log "container Caddy mode: Structify owns public 80/443"
  if [[ "$EXECUTE" == "1" ]]; then
    require_command ss
    require_command docker
    caddy_container=""
    for public_port in 80 443; do
      public_listeners="$(ss -H -ltn "sport = :$public_port" 2>/dev/null || true)"
      if [[ -n "$public_listeners" ]]; then
        if [[ -z "$caddy_container" ]]; then
          caddy_container="$(running_compose_caddy || true)"
        fi
        [[ -n "$caddy_container" ]] && caddy_binds_public_tcp_port "$caddy_container" "$public_port" \
          || die "public TCP port $public_port is already bound; CADDY_MODE=container requires a dedicated host"
        log "public TCP port $public_port is already served by the running caddy service for this Compose project"
      fi
    done
    log "public TCP ports 80 and 443 are available for container Caddy or served by the running Compose Caddy service"
    if [[ -n "${origin_cert_dir:-}" ]]; then
      validate_origin_ca_caddy "$origin_cert_dir"
    fi
  fi
fi

memory_mib() {
  local key="$1"
  local value="$2"
  [[ "$value" =~ ^([0-9]+)([mM])$ ]] \
    || die "$key must be a whole number of MiB with an m suffix"
  local amount="${BASH_REMATCH[1]}"
  (( 10#$amount >= 16 )) || die "$key must be at least 16 MiB"
  printf '%s\n' "$((10#$amount))"
}

whole_mib() {
  local key="$1"
  local value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || die "$key must be a whole number of MiB"
  printf '%s\n' "$((10#$value))"
}

configured_memory_mib() {
  local key="$1"
  local fallback="$2"
  local value
  value="$(env_value "$key")"
  value="${value:-$fallback}"
  memory_mib "$key" "$value"
}

if [[ "$EXECUTE" == "1" ]]; then
  memory_profile="$(env_value MEMORY_PROFILE)"
  memory_profile="${memory_profile:-low-memory}"
  case "$memory_profile" in
    low-memory)
      profile_minimum_mb=1024
      profile_reserve_mb=256
      profile_hard_limit_mb=1088
      ;;
    standard)
      profile_minimum_mb=1536
      profile_reserve_mb=384
      profile_hard_limit_mb=2048
      ;;
    *) die "MEMORY_PROFILE must be low-memory or standard" ;;
  esac

  minimum_available_memory_mb="$(env_value MIN_AVAILABLE_MEMORY_MB)"
  minimum_available_memory_mb="${minimum_available_memory_mb:-$profile_minimum_mb}"
  minimum_available_memory_mb="$(whole_mib MIN_AVAILABLE_MEMORY_MB "$minimum_available_memory_mb")"
  (( minimum_available_memory_mb >= profile_minimum_mb )) \
    || die "MIN_AVAILABLE_MEMORY_MB must be at least ${profile_minimum_mb} MiB for MEMORY_PROFILE=$memory_profile"

  memory_reserve_mb="$(env_value MEMORY_RESERVE_MB)"
  memory_reserve_mb="${memory_reserve_mb:-$profile_reserve_mb}"
  memory_reserve_mb="$(whole_mib MEMORY_RESERVE_MB "$memory_reserve_mb")"
  (( memory_reserve_mb >= 128 )) || die "MEMORY_RESERVE_MB must be at least 128 MiB"

  mysql_limit_mb="$(configured_memory_mib MYSQL_MEMORY_LIMIT 384m)"
  node_limit_mb="$(configured_memory_mib NODE_MEMORY_LIMIT 256m)"
  spring_limit_mb="$(configured_memory_mib SPRING_MEMORY_LIMIT 384m)"
  caddy_limit_mb="$(configured_memory_mib CADDY_MEMORY_LIMIT 64m)"
  mysql_reservation_mb="$(configured_memory_mib MYSQL_MEMORY_RESERVATION 256m)"
  node_reservation_mb="$(configured_memory_mib NODE_MEMORY_RESERVATION 160m)"
  spring_reservation_mb="$(configured_memory_mib SPRING_MEMORY_RESERVATION 288m)"
  caddy_reservation_mb="$(configured_memory_mib CADDY_MEMORY_RESERVATION 64m)"
  node_max_old_space_mb="$(env_value NODE_MAX_OLD_SPACE_MB)"
  node_max_old_space_mb="${node_max_old_space_mb:-160}"
  node_max_old_space_mb="$(whole_mib NODE_MAX_OLD_SPACE_MB "$node_max_old_space_mb")"
  (( node_max_old_space_mb < node_limit_mb )) \
    || die "NODE_MAX_OLD_SPACE_MB must be below NODE_MEMORY_LIMIT"

  for service in mysql node spring caddy; do
    limit_var="${service}_limit_mb"
    reservation_var="${service}_reservation_mb"
    (( ${!reservation_var} <= ${!limit_var} )) \
      || die "${service^^}_MEMORY_RESERVATION must not exceed ${service^^}_MEMORY_LIMIT"
  done

  total_hard_limit_mb=$((mysql_limit_mb + node_limit_mb + spring_limit_mb + caddy_limit_mb))
  total_reservation_mb=$((mysql_reservation_mb + node_reservation_mb + spring_reservation_mb + caddy_reservation_mb))
  (( total_hard_limit_mb <= profile_hard_limit_mb )) \
    || die "${memory_profile} service memory limits total ${total_hard_limit_mb} MiB exceeds hard cap ${profile_hard_limit_mb} MiB"
  evidence_budget_mb=$((total_reservation_mb + memory_reserve_mb))
  memory_budget_mb="$(env_value MEMORY_BUDGET_MB)"
  memory_budget_mb="${memory_budget_mb:-$evidence_budget_mb}"
  memory_budget_mb="$(whole_mib MEMORY_BUDGET_MB "$memory_budget_mb")"
  (( memory_budget_mb >= evidence_budget_mb )) \
    || die "MEMORY_BUDGET_MB ${memory_budget_mb} MiB is below declared reservations plus reserve ${evidence_budget_mb} MiB"
  hard_budget_mb=$((total_hard_limit_mb + memory_reserve_mb))
  effective_memory_budget_mb="$memory_budget_mb"
  (( effective_memory_budget_mb >= hard_budget_mb )) || effective_memory_budget_mb="$hard_budget_mb"

  available_memory_kib="$(awk '/^MemAvailable:/ { print $2; exit }' /proc/meminfo 2>/dev/null || true)"
  [[ "$available_memory_kib" =~ ^[0-9]+$ ]] \
    || die "cannot read MemAvailable from /proc/meminfo; execute deployment only on a Linux host"
  available_memory_mb=$((10#$available_memory_kib / 1024))
  (( available_memory_mb >= minimum_available_memory_mb )) \
    || die "available memory ${available_memory_mb} MiB is below configured floor ${minimum_available_memory_mb} MiB"
  (( available_memory_mb >= effective_memory_budget_mb )) \
    || die "configured memory budget ${memory_budget_mb} MiB (effective minimum ${effective_memory_budget_mb} MiB) exceeds available memory ${available_memory_mb} MiB"
  log "memory profile ${memory_profile}: service hard cap ${total_hard_limit_mb} MiB; reservations ${total_reservation_mb} MiB + reserve ${memory_reserve_mb} MiB"
  log "memory budget ${memory_budget_mb} MiB (effective ${effective_memory_budget_mb} MiB) meets available memory ${available_memory_mb} MiB"
fi

mail_enabled="$(env_value AUTH_MAIL_ENABLED)"
mail_enabled="${mail_enabled:-false}"
if [[ "$mail_enabled" =~ ^(true|1|yes|on)$ ]]; then
  for key in SMTP_HOST SMTP_PORT SMTP_USER SMTP_PASS SMTP_FROM; do
    value="$(env_value "$key")"
    [[ -n "$value" ]] || die "$key is required when AUTH_MAIL_ENABLED is true"
    [[ "$value" != __*__ ]] || die "$key still contains a placeholder"
  done
else
  log "mail integration disabled (AUTH_MAIL_ENABLED is false)"
fi

for key in JUDGE0_BASE_URL PISTON_BASE_URL; do
  value="$(env_value "$key")"
  if [[ -n "$value" ]]; then
    [[ "$value" != __*__ ]] || die "$key still contains a placeholder"
    [[ "$value" =~ ^https:// ]] || die "$key must use HTTPS"
  fi
done

expected_cors_origins="https://structify.cn,https://admin.structify.cn"
[[ "$(env_value CORS_ALLOWED_ORIGINS)" == "$expected_cors_origins" ]] \
  || die "CORS_ALLOWED_ORIGINS must be exactly $expected_cors_origins"
[[ -z "$(env_value BOOTSTRAP_ADMIN_EMAIL)" ]] || die "BOOTSTRAP_ADMIN_EMAIL must be empty in production"
bootstrap_provision_enabled="$(env_value BOOTSTRAP_ADMIN_PROVISION_ENABLED)"
bootstrap_provision_enabled="${bootstrap_provision_enabled:-false}"
case "$bootstrap_provision_enabled" in
  false|0|no|off)
    for key in BOOTSTRAP_ADMIN_PROVISION_EMAIL BOOTSTRAP_ADMIN_PROVISION_USERNAME BOOTSTRAP_ADMIN_PROVISION_PASSWORD; do
      [[ -z "$(env_value "$key")" ]] || die "$key must be empty while BOOTSTRAP_ADMIN_PROVISION_ENABLED is false"
    done
    ;;
  true|1|yes|on)
    for key in BOOTSTRAP_ADMIN_PROVISION_EMAIL BOOTSTRAP_ADMIN_PROVISION_USERNAME BOOTSTRAP_ADMIN_PROVISION_PASSWORD; do
      value="$(env_value "$key")"
      [[ -n "$value" ]] || die "$key is required when BOOTSTRAP_ADMIN_PROVISION_ENABLED is true"
      [[ "$value" != __*__ ]] || die "$key still contains a placeholder"
    done
    log "one-time administrator provisioning is enabled; clear BOOTSTRAP_ADMIN_PROVISION_* after successful startup"
    ;;
  *) die "BOOTSTRAP_ADMIN_PROVISION_ENABLED must be boolean" ;;
esac
[[ -z "$(env_value TEACHER_EMAILS)" ]] || die "TEACHER_EMAILS must be empty in production"
[[ "$(env_value ALLOW_FIRST_USER_TEACHER)" =~ ^(false|0|no|off)$ ]] || die "ALLOW_FIRST_USER_TEACHER must be false"
[[ "$(env_value JWT_SECRET)" =~ ^.{64,}$ ]] || die "JWT_SECRET must be at least 64 characters"
[[ "$(env_value NODE_COMPAT_JWT_SECRET)" =~ ^.{64,}$ ]] || die "NODE_COMPAT_JWT_SECRET must be at least 64 characters"
[[ "$(env_value JWT_SECRET)" != "$(env_value NODE_COMPAT_JWT_SECRET)" ]] || die "JWT_SECRET and NODE_COMPAT_JWT_SECRET must differ"
node_compat_enabled="$(env_value NODE_COMPAT_ENABLED)"
node_compat_enabled="${node_compat_enabled:-true}"
[[ "$node_compat_enabled" =~ ^(true|false|1|0|yes|no|on|off)$ ]] || die "NODE_COMPAT_ENABLED must be boolean"
[[ "$(env_value AUTH_COOKIE_SECURE)" != "false" ]] || die "AUTH_COOKIE_SECURE must not be false"
[[ "$(env_value AUTH_EXPOSE_DEV_CODE)" =~ ^(false|0|no|off)$ ]] || die "AUTH_EXPOSE_DEV_CODE must be false"

[[ -z "$(env_value VERIFICATION_CODE_FILE)" ]] || die "VERIFICATION_CODE_FILE must be empty in production"
[[ "$(env_value KNOWLEDGE_DEBUG_API)" =~ ^(false|0|no|off)$ ]] || die "KNOWLEDGE_DEBUG_API must be false"

for path_key in KNOWLEDGE_DIR_HOST RESOURCE_DIR_HOST PRESENTATION_DIR_HOST PDF_SOURCE_DIR_HOST; do
  path_value="$(env_value "$path_key")"
  [[ "$path_value" == /* ]] || die "$path_key must be an absolute Linux path"
  if [[ "$EXECUTE" == "1" && ! -d "$path_value" ]]; then
    die "$path_key does not exist: $path_value"
  fi
done

require_command docker
[[ -f "$COMPOSE_FILE" ]] || die "Compose file is missing: $COMPOSE_FILE"
if [[ "$EXECUTE" == "1" ]]; then
  compose config --quiet
else
  log "dry-run: Docker Compose rendering was not executed"
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet
fi

log "preflight passed (no network or server connection was made)"
