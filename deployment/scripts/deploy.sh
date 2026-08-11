#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

EXECUTE=0
SKIP_BUILD=0
REFRESH_CADDY=0
CONFIRM=""
RELEASE=""
PRIVATE_ROOT=""
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/structify}"

usage() {
  cat <<'EOF'
Usage: deploy.sh --release RELEASE [--env-file FILE] [--private-root DIR]
                 [--skip-build] [--refresh-caddy]
                 [--execute --confirm DEPLOY-structify.cn]

Default mode validates the release name and prints the build/backup/migration
plan. --skip-build requires the immutable Node/Spring release images to already
exist locally. Execute mode otherwise builds those images, captures a backup,
starts MySQL, and lets Spring run Flyway migrations. In host Caddy mode it never
touches public 80/443; the host operator installs and reloads the reviewed site
block separately. --refresh-caddy is container mode only; it intentionally
recreates the verified Compose Caddy and requires
--confirm REFRESH-CADDY-structify.cn. DNS is never changed by this script.
EOF
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) RELEASE="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --private-root) PRIVATE_ROOT="$2"; shift 2 ;;
    --backup-root) BACKUP_ROOT="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --refresh-caddy) REFRESH_CADDY=1; shift ;;
    --execute) EXECUTE=1; shift ;;
    --confirm) CONFIRM="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done
[[ -n "$RELEASE" ]] || die "--release is required"
safe_release_tag "$RELEASE"
require_command docker

configured_node_image="$(env_value NODE_IMAGE)"
configured_spring_image="$(env_value SPRING_IMAGE)"
[[ "$configured_node_image" == "structify-node:$RELEASE" ]] || die "NODE_IMAGE must be structify-node:$RELEASE in the environment file"
[[ "$configured_spring_image" == "structify-spring:$RELEASE" ]] || die "SPRING_IMAGE must be structify-spring:$RELEASE in the environment file"
caddy_mode_value="$(caddy_mode)"
if [[ "$REFRESH_CADDY" == "1" && "$caddy_mode_value" != "container" ]]; then
  die "--refresh-caddy requires CADDY_MODE=container"
fi
node_port="$(node_host_port)"
spring_port="$(spring_host_port)"

bootstrap_data_services() {
  local running_mysql running_node
  running_mysql="$(compose ps --status running -q mysql 2>/dev/null || true)"
  running_node="$(compose ps --status running -q node 2>/dev/null || true)"

  if [[ -n "$running_mysql" && -n "$running_node" ]]; then
    log "existing data services are running; capturing a pre-release backup"
    return
  fi
  if [[ -n "$running_mysql" || -n "$running_node" ]]; then
    die "data services are only partially running; inspect and recover them before deployment"
  fi

  log "bootstrap data services before the first Flyway migration"
  compose up -d --no-build mysql node
  for attempt in $(seq 1 30); do
    if compose exec -T mysql mysqladmin ping -h 127.0.0.1 --silent >/dev/null 2>&1 \
      && curl --fail --silent --max-time 5 "http://127.0.0.1:$node_port/healthz" >/dev/null 2>&1; then
      return
    fi
    [[ "$attempt" -lt 30 ]] || die "bootstrap data services did not become healthy; inspect compose logs"
    sleep 2
  done
}

verify_release_images() {
  docker image inspect "$configured_node_image" >/dev/null 2>&1 \
    || die "NODE_IMAGE is not available locally for --skip-build: $configured_node_image"
  docker image inspect "$configured_spring_image" >/dev/null 2>&1 \
    || die "SPRING_IMAGE is not available locally for --skip-build: $configured_spring_image"
}

container_caddy_config_dir() {
  local value
  value="$(env_value CADDY_CONFIG_DIR_HOST)"
  [[ -n "$value" ]] || value="/srv/structify/caddy"
  [[ "$value" == /* ]] || die "CADDY_CONFIG_DIR_HOST must be an absolute Linux path"
  printf '%s\n' "$value"
}

running_compose_caddy() {
  local expected_project caddy_container caddy_project caddy_service caddy_running
  local -a caddy_containers=()

  expected_project="$(env_value COMPOSE_PROJECT_NAME)"
  [[ -n "$expected_project" ]] || expected_project="structify"
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

caddy_uses_stable_config_bind() {
  local caddy_container="$1"
  local config_dir="$2"
  local expected_source mount_source

  expected_source="$(realpath -e "$config_dir" 2>/dev/null || true)"
  [[ -n "$expected_source" ]] || return 1
  mount_source="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/etc/caddy"}}{{.Source}}{{end}}{{end}}' "$caddy_container" 2>/dev/null || true)"
  [[ -n "$mount_source" ]] || return 1
  mount_source="$(realpath -e "$mount_source" 2>/dev/null || true)"
  [[ "$mount_source" == "$expected_source" ]]
}

caddy_has_loopback_admin() {
  local caddy_container="$1"
  local status=0

  docker exec "$caddy_container" /bin/sh -ec \
    "grep -Eq '^[[:space:]]*admin[[:space:]]+127\\.0\\.0\\.1:2019([[:space:]]|$)' /etc/caddy/Caddyfile || exit 42" \
    >/dev/null 2>&1 || status=$?
  if (( status == 0 )); then
    return 0
  fi
  if (( status == 42 )); then
    return 1
  fi
  die "cannot inspect the running Compose Caddy admin configuration"
}

sync_container_caddyfile() {
  local config_dir="$1"
  local config_dir_real origin_ca_mountpoint temporary_file
  local source_file="$DEPLOY_DIR/Caddyfile.production"

  [[ -f "$source_file" ]] || die "container Caddyfile is missing: $source_file"
  if [[ -e "$config_dir" ]]; then
    [[ -d "$config_dir" && ! -L "$config_dir" ]] \
      || die "CADDY_CONFIG_DIR_HOST must be a real directory: $config_dir"
  else
    mkdir -m 755 -p "$config_dir"
  fi
  [[ -d "$config_dir" && ! -L "$config_dir" ]] \
    || die "CADDY_CONFIG_DIR_HOST must be a real directory: $config_dir"
  config_dir_real="$(realpath -e "$config_dir")"
  origin_ca_mountpoint="$config_dir_real/origin-ca"
  if [[ -e "$origin_ca_mountpoint" || -L "$origin_ca_mountpoint" ]]; then
    [[ -d "$origin_ca_mountpoint" && ! -L "$origin_ca_mountpoint" ]] \
      || die "container Caddy Origin CA mountpoint must be a real directory: $origin_ca_mountpoint"
  else
    mkdir -m 755 -p "$origin_ca_mountpoint"
  fi

  temporary_file="$(mktemp "$config_dir_real/.Caddyfile.XXXXXX")"
  cp -- "$source_file" "$temporary_file"
  chmod 644 "$temporary_file"
  mv -f -- "$temporary_file" "$config_dir_real/Caddyfile"
  log "atomically synchronized the container Caddyfile into $config_dir_real"
}

reload_container_caddy() {
  local caddy_container="$1"
  local origin_cert_dir acme_email acme_tls_directive

  origin_cert_dir="$(env_value ORIGIN_CERT_DIR_HOST)"
  if [[ -n "$origin_cert_dir" ]]; then
    docker exec \
      --env 'CADDY_TLS_DIRECTIVE=tls /etc/caddy/origin-ca/origin.crt /etc/caddy/origin-ca/origin.key' \
      --env 'CADDY_EMAIL_DIRECTIVE=' \
      "$caddy_container" caddy reload --address 127.0.0.1:2019 --config /etc/caddy/Caddyfile --adapter caddyfile
    return
  fi

  acme_email="$(env_value ACME_EMAIL)"
  [[ -n "$acme_email" ]] || die "ACME_EMAIL is required to reload container Caddy"
  acme_tls_directive=$'tls {\n  issuer acme {\n    disable_tlsalpn_challenge\n  }\n}'
  docker exec \
    --env "CADDY_TLS_DIRECTIVE=$acme_tls_directive" \
    --env "CADDY_EMAIL_DIRECTIVE=email $acme_email" \
    "$caddy_container" caddy reload --address 127.0.0.1:2019 --config /etc/caddy/Caddyfile --adapter caddyfile
}

start_container_caddy() {
  docker compose --profile container-caddy --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-build caddy
}

reconcile_container_caddy() {
  local config_dir caddy_container stable_caddy=0

  config_dir="$(container_caddy_config_dir)"
  caddy_container="$(running_compose_caddy || true)"
  if [[ -n "$caddy_container" ]] \
    && caddy_uses_stable_config_bind "$caddy_container" "$config_dir" \
    && caddy_has_loopback_admin "$caddy_container"; then
    stable_caddy=1
  fi
  sync_container_caddyfile "$config_dir"

  if [[ -z "$caddy_container" ]]; then
    log "container Caddy is not running; creating it with the stable configuration bind"
    start_container_caddy
    return
  fi

  if [[ "$REFRESH_CADDY" == "1" ]]; then
    log "explicitly refreshing the running Compose Caddy"
    docker stop "$caddy_container"
    docker rm "$caddy_container"
    start_container_caddy
    return
  fi

  if [[ "$stable_caddy" == "1" ]]; then
    log "reloading the running Compose Caddy from its stable configuration bind"
    reload_container_caddy "$caddy_container"
    return
  fi

  log "migrating legacy Compose Caddy to the stable configuration bind"
  docker stop "$caddy_container"
  docker rm "$caddy_container"
  start_container_caddy
}

if [[ "$EXECUTE" != "1" ]]; then
  log "dry-run deploy plan for release $RELEASE"
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet
  if [[ "$SKIP_BUILD" == "1" ]]; then
    log "--skip-build: verify immutable release images before deployment"
    print_command docker image inspect "$configured_node_image"
    print_command docker image inspect "$configured_spring_image"
  else
    print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" build node spring-api
  fi
  log "if no Node/MySQL containers are running, bootstrap data services before the persistent-data backup"
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-build mysql node
  print_command "$SCRIPT_DIR/backup.sh" --env-file "$ENV_FILE" --backup-root "$BACKUP_ROOT" --private-root "${PRIVATE_ROOT:-/srv/structify/private}" --execute --confirm BACKUP-structify.cn
  print_command docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-build node spring-api
  if [[ "$caddy_mode_value" == "container" ]]; then
    if [[ "$REFRESH_CADDY" == "1" ]]; then
      log "container Caddy mode: explicit refresh stops/removes the verified running Compose Caddy before creation"
      log "execute requires --confirm REFRESH-CADDY-structify.cn"
    else
      log "container Caddy mode: synchronize the stable Caddyfile bind and reload a matching running Compose Caddy"
      log "a legacy release-bound or admin-disabled Caddy is stopped and removed before one replacement is created"
    fi
  else
    log "host Caddy mode: validate and reload the existing host configuration after the application health checks"
  fi
  if [[ "$REFRESH_CADDY" == "1" ]]; then
    log "re-run with --execute --confirm REFRESH-CADDY-structify.cn after review"
  else
    log "re-run with --execute --confirm DEPLOY-structify.cn after review"
  fi
  exit 0
fi

if [[ "$REFRESH_CADDY" == "1" ]]; then
  [[ "$CONFIRM" == "REFRESH-CADDY-structify.cn" ]] \
    || die "--refresh-caddy requires --confirm REFRESH-CADDY-structify.cn"
else
  [[ "$CONFIRM" == "DEPLOY-structify.cn" ]] || die "deploy requires --confirm DEPLOY-structify.cn"
fi
[[ -n "$PRIVATE_ROOT" ]] || PRIVATE_ROOT="/srv/structify/private"
require_command curl
"$SCRIPT_DIR/preflight.sh" --env-file "$ENV_FILE" --compose-file "$COMPOSE_FILE" --execute

mkdir -m 700 -p "$BACKUP_ROOT"
if [[ -r "$BACKUP_ROOT/last-release.env" ]]; then
  cp -p "$BACKUP_ROOT/last-release.env" "$BACKUP_ROOT/previous-release.env"
  chmod 600 "$BACKUP_ROOT/previous-release.env"
fi

if [[ "$SKIP_BUILD" == "1" ]]; then
  verify_release_images
  log "--skip-build: using verified immutable local release images"
else
  compose build --pull=false node spring-api
fi

bootstrap_data_services
"$SCRIPT_DIR/backup.sh" --env-file "$ENV_FILE" --backup-root "$BACKUP_ROOT" --private-root "$PRIVATE_ROOT" --execute --confirm BACKUP-structify.cn

compose up -d --no-build node spring-api
if [[ "$caddy_mode_value" == "container" ]]; then
  reconcile_container_caddy
else
  log "host Caddy mode: application services are ready on loopback ports $node_port/$spring_port; no public listener was changed"
fi

for attempt in $(seq 1 30); do
  if curl --fail --silent --max-time 5 "http://127.0.0.1:$node_port/healthz" >/dev/null 2>&1 \
    && curl --fail --silent --max-time 5 "http://127.0.0.1:$spring_port/actuator/health" >/dev/null 2>&1; then
    break
  fi
  [[ "$attempt" -lt 30 ]] || die "services did not become healthy; inspect compose logs"
  sleep 2
done

node_container="$(compose ps -q node)"
spring_container="$(compose ps -q spring-api)"
[[ -n "$node_container" && -n "$spring_container" ]] || die "cannot record release: application container is missing"
node_image_id="$(docker inspect --format '{{.Image}}' "$node_container")"
spring_image_id="$(docker inspect --format '{{.Image}}' "$spring_container")"
printf 'NODE_IMAGE=%s\nSPRING_IMAGE=%s\nNODE_IMAGE_ID=%s\nSPRING_IMAGE_ID=%s\n' \
  "$configured_node_image" "$configured_spring_image" "$node_image_id" "$spring_image_id" > "$BACKUP_ROOT/last-release.env"
chmod 600 "$BACKUP_ROOT/last-release.env"
log "release $RELEASE is running; DNS remains unchanged"
