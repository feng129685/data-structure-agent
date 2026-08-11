#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

RELEASE=""
PRIVATE_ROOT="/srv/structify/private"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/structify}"
RELEASE_ROOT="${RELEASE_ROOT:-/srv/structify/releases}"
RETAIN=2
EXECUTE=0
CONFIRM=""
SKIP_BUILD=0

usage() {
  cat <<'EOF'
Usage: release.sh --release RELEASE [options]

One audited production entry point. It delegates to deploy.sh, verifies
loopback health, records the active source release, and only then keeps the
active release plus one rollback release. It never changes DNS, Git history,
private resources, or backups.

Options:
  --env-file FILE       Production environment file (default: /etc/structify/structify.env)
  --private-root DIR    Read-only private-resource root
  --backup-root DIR     Backup root
  --release-root DIR    Versioned source-release root
  --retain 2            Keep the active release and one previous release
  --skip-build          Require already-built immutable release images
  --execute             Perform the deployment after all gates pass
  --confirm VALUE       Required with --execute: RELEASE-structify.cn
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) RELEASE="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --private-root) PRIVATE_ROOT="$2"; shift 2 ;;
    --backup-root) BACKUP_ROOT="$2"; shift 2 ;;
    --release-root) RELEASE_ROOT="$2"; shift 2 ;;
    --retain) RETAIN="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --execute) EXECUTE=1; shift ;;
    --confirm) CONFIRM="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ -n "$RELEASE" ]] || die "--release is required"
safe_release_tag "$RELEASE"
[[ "$RETAIN" =~ ^[0-9]+$ ]] || die "--retain must be a whole number"
(( 10#$RETAIN >= 2 )) || die "--retain must be at least 2 to preserve rollback"
[[ "$RELEASE_ROOT" == /* ]] || die "--release-root must be an absolute Linux path"
[[ "$PRIVATE_ROOT" == /* ]] || die "--private-root must be an absolute Linux path"
[[ "$BACKUP_ROOT" == /* ]] || die "--backup-root must be an absolute Linux path"

release_root_real() {
  [[ -d "$RELEASE_ROOT" ]] || die "release root does not exist: $RELEASE_ROOT"
  realpath -e "$RELEASE_ROOT"
}

verify_release_location() {
  local root_real repo_real expected_real repo_identity expected_identity
  root_real="$(release_root_real)"
  repo_real="$(realpath -e "$REPO_DIR")"
  expected_real="$(realpath -e "$root_real/$RELEASE")"
  repo_identity="$(stat -Lc '%d:%i' "$repo_real")"
  expected_identity="$(stat -Lc '%d:%i' "$expected_real")"
  [[ "$repo_identity" == "$expected_identity" ]] \
    || die "release script must run from $expected_real, not $repo_real"
}

is_retained_release() {
  local candidate="$1"
  shift
  local retained
  for retained in "$@"; do
    [[ "$candidate" == "$retained" ]] && return 0
  done
  return 1
}

is_release_directory() {
  local directory="$1"
  [[ -d "$directory" && ! -L "$directory" && -f "$directory/deployment/docker-compose.production.yml" ]]
}

prune_release_directories() {
  local root_real="$1"
  shift
  local retained=("$@")
  local tag target target_real

  for target in "$root_real"/*; do
    is_release_directory "$target" || continue
    tag="$(basename -- "$target")"
    safe_release_tag "$tag" || die "release root contains an unsafe directory name"
    is_retained_release "$tag" "${retained[@]}" && continue
    target_real="$(realpath -e "$target")"
    [[ "$target_real" == "$root_real/"* ]] || die "refusing to prune outside release root"
    log "pruning obsolete source release $tag"
    rm -rf -- "$target_real"
  done
}

prune_release_images() {
  local repository image release_tag
  local retained=("$@")
  require_command docker
  while IFS='|' read -r repository image; do
    [[ "$repository" =~ ^structify-(node|spring):(.+)$ ]] || continue
    release_tag="${BASH_REMATCH[2]}"
    safe_release_tag "$release_tag" || continue
    is_retained_release "$release_tag" "${retained[@]}" && continue
    log "pruning obsolete release image $repository"
    docker image rm "$repository" >/dev/null 2>&1 || warn "could not remove in-use image $repository"
  done < <(docker image ls --format '{{.Repository}}:{{.Tag}}|{{.ID}}')
}

run_deploy() {
  local args=(
    "$SCRIPT_DIR/deploy.sh"
    --env-file "$ENV_FILE"
    --private-root "$PRIVATE_ROOT"
    --backup-root "$BACKUP_ROOT"
    --release "$RELEASE"
  )
  [[ "$SKIP_BUILD" == "1" ]] && args+=(--skip-build)
  if [[ "$EXECUTE" == "1" ]]; then
    args+=(--execute --confirm DEPLOY-structify.cn)
  fi
  "${args[@]}"
}

if [[ "$EXECUTE" != "1" ]]; then
  verify_release_location
  log "dry-run release plan for $RELEASE"
  run_deploy
  "$SCRIPT_DIR/health-check.sh" --env-file "$ENV_FILE"
  log "only after deployment health checks succeed will retention prune release directories and images"
  exit 0
fi

[[ "$CONFIRM" == "RELEASE-structify.cn" ]] || die "release requires --confirm RELEASE-structify.cn"
verify_release_location

previous_release=""
active_marker="$(release_root_real)/active-release"
if [[ -f "$active_marker" && ! -L "$active_marker" ]]; then
  previous_release="$(tr -d '\r\n' < "$active_marker")"
  [[ -z "$previous_release" ]] || safe_release_tag "$previous_release"
fi

run_deploy
"$SCRIPT_DIR/health-check.sh" --env-file "$ENV_FILE" --execute

# Retention runs only after deployment health checks succeed.
release_root="$(release_root_real)"
retained=("$RELEASE")
if [[ -n "$previous_release" && "$previous_release" != "$RELEASE" && -d "$release_root/$previous_release" ]]; then
  retained+=("$previous_release")
fi

while (( ${#retained[@]} < RETAIN )); do
  candidate=""
  while IFS= read -r tag; do
    is_release_directory "$release_root/$tag" || continue
    safe_release_tag "$tag" || continue
    if ! is_retained_release "$tag" "${retained[@]}"; then
      candidate="$tag"
      break
    fi
  done < <(find "$release_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -Vr)
  [[ -n "$candidate" ]] || break
  is_retained_release "$candidate" "${retained[@]}" && break
  retained+=("$candidate")
done

tmp_marker="$(mktemp "$release_root/.active-release.XXXXXX")"
printf '%s\n' "$RELEASE" > "$tmp_marker"
chmod 600 "$tmp_marker"
mv -f "$tmp_marker" "$active_marker"

prune_release_directories "$release_root" "${retained[@]}"
prune_release_images "${retained[@]}"
log "release $RELEASE is healthy; retained releases: ${retained[*]}"
