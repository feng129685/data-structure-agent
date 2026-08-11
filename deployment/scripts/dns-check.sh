#!/usr/bin/env bash
set -Eeuo pipefail

DOMAIN="${DOMAIN:-structify.cn}"
EXPECTED=""
EXECUTE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain) DOMAIN="$2"; shift 2 ;;
    --expected-ip) EXPECTED="$2"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    -h|--help) printf '%s\n' 'Usage: dns-check.sh [--domain HOST] [--expected-ip IP] [--execute]'; exit 0 ;;
    *) printf 'unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

if [[ "$EXECUTE" != "1" ]]; then
  printf '%s\n' "dry-run DNS cutover verification for $DOMAIN"
  printf '%s\n' "+ dig +short A $DOMAIN"
  printf '%s\n' "+ dig +short AAAA $DOMAIN"
  printf '+ curl --resolve %s:443:NEW_IP https://%s/healthz\n' "$DOMAIN" "$DOMAIN"
  printf '%s\n' 'No DNS provider API is configured; this script never changes records.'
  exit 0
fi

command -v dig >/dev/null 2>&1 || { printf '%s\n' 'dig is required' >&2; exit 1; }
mapfile -t addresses < <(dig +short A "$DOMAIN" | sed '/^[[:space:]]*$/d')
printf '%s\n' "A records for $DOMAIN: ${addresses[*]:-none}"
if [[ -n "$EXPECTED" ]]; then
  printf '%s\n' "${addresses[@]}" | grep -Fxq "$EXPECTED" || { printf '%s\n' "expected address is not visible yet" >&2; exit 1; }
fi
printf '%s\n' 'DNS records are visible. Run smoke.sh separately before switching traffic.'
