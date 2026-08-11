# Structify Production Release Runbook

## Purpose And Scope

This is the operator checklist for publishing a reviewed Structify release to
an existing Linux host. It applies to the dual-backend topology:

- Node compatibility service: `/api/*`, `/presentation/*`, static content, and
  `GET /healthz`.
- Spring service: `/api/v1/*`, including administrator APIs, Flyway migrations,
  and `GET /actuator/health` on loopback only.
- Compose-managed Caddy container: public TLS, `structify.cn`, and
  `admin.structify.cn` routing. This is the verified production topology for
  `v1.0.16-structify`.

It deliberately separates three authorities:

1. `upload-release.ps1` transfers and verifies source only.
2. `release.sh` is the only normal production rollout entry point.
3. The selected Caddy mode and DNS operator establish public routing only after
   the application release is healthy.

Do not use DPanel as a replacement for these scripts. Do not deploy from a
working tree, change DNS before the Caddy handoff is ready, or read, print, or
copy production secrets into this repository.

This document contains placeholders only. Values such as the production host,
SSH user, private path, environment file, provider credentials, database
credentials, tokens, and API keys remain outside source control.

## Preconditions

- Local tests, production configuration verification, builds, and
  `git diff --check` have passed for the exact release artifact.
- The release archive contains `backend/node/server.js`,
  `backend/spring/pom.xml`, `deployment/docker-compose.production.yml`, and
  `deployment/scripts/release.sh`.
- A known SSH host key is already recorded locally. The uploader uses
  `StrictHostKeyChecking=yes`; do not weaken this setting.
- The new environment file preserves the same Compose project and storage
  identity as the prior release. It changes only reviewed non-secret release
  settings, including immutable Node/Spring image tags and the exact CORS
  value below.
- `CORS_ALLOWED_ORIGINS` is exactly
  `https://structify.cn,https://admin.structify.cn`.
- The actual production environment uses `CADDY_MODE=container` on a dedicated
  host. The Compose `caddy` profile owns public `80/443`; no host Caddy service
  may hold those ports. `CADDY_CONFIG_DIR_HOST` is a stable host directory outside
  release directories (default `/srv/structify/caddy`) and is mounted at
  `/etc/caddy`. During a repeat release, the already-running `caddy` service
  for this same Compose project remains in place, reloads from that stable
  bind, and retains the public ports. It is not an external port conflict.
- The stable Caddy directory contains a real `origin-ca` subdirectory. The
  deployment script creates it before container creation because Docker
  cannot create a nested mountpoint below a read-only `/etc/caddy` bind.
- Host-Caddy mode remains supported for a separately reviewed shared-host
  environment. It requires `HOST_CADDY_CONFIG` and
  `Caddyfile.host.production`; it is not interchangeable with the current
  container-Caddy production topology.
- The operator has a maintenance window for the temporary application outage
  needed on a low-memory host.

## Release Sequence

### 1. Upload The Immutable Source Archive

Run the Windows-side uploader from the reviewed checkout. It validates archive
paths, copies via SCP, verifies SHA-256 on the host, and atomically extracts to
the versioned release directory. It does not run Compose, read a server
environment file, alter Caddy, or change DNS.

```powershell
.\deployment\scripts\upload-release.ps1 `
  -Artifact <release-archive.zip> `
  -Release <immutable-release-tag> `
  -HostName <production-host> `
  -User <ssh-user> `
  -Execute -Confirm UPLOAD-structify.cn
```

Keep the uploader's local/remote SHA-256 confirmation with the release record.
If upload or checksum verification fails, stop. Do not extract an archive by
hand into the release directory.

### 2. Prepare The New Environment File Without Revealing It

On the host, retain the prior release environment file for rollback context and
create a new `0600` environment file for the new tag. Use a secure editor or a
host-side secret-management procedure. Do not print it or place it in the
release archive.

The new file must set:

- `NODE_IMAGE=structify-node:<immutable-release-tag>`
- `SPRING_IMAGE=structify-spring:<immutable-release-tag>`
- `CORS_ALLOWED_ORIGINS=https://structify.cn,https://admin.structify.cn`
- `CADDY_CONFIG_DIR_HOST=/srv/structify/caddy`, or another stable
  directory outside the versioned release root when container Caddy is used.

All secret values, private resource paths, Compose project identity, and host
Caddy configuration references must remain consistent with the reviewed prior
environment. `preflight.sh --execute` is the source of truth for validating the
file; never lower memory limits, reservations, the reserve, or its minimums to
force a pass.

### 3. Take A Backup Through The Currently Running Release

Use the old release directory, old Compose model, and old environment file for
the pre-release backup. This produces a recovery point before a new image can
touch persistent volumes or Flyway can migrate MySQL.

```bash
old_release=<old-release-directory>
old_env=<old-environment-file>

bash "$old_release/deployment/scripts/backup.sh" \
  --env-file "$old_env" \
  --private-root <private-resource-root> \
  --backup-root <backup-root> \
  --execute --confirm BACKUP-structify.cn
```

Confirm the command reports one new timestamped backup containing its manifest
and SHA-256 checksums. A failed or incomplete backup is a stop condition.

### 4. Make A Low-Memory Host Eligible For Preflight

The default `low-memory` profile requires at least 1,344 MiB of effective
available memory. Existing application containers can leave a host below that
gate even when the configured containers would fit after replacement.

After the old-release backup succeeds, stop the three application/data services
with the old Compose file. Keep the Compose-managed `caddy` service running so
it retains public `80/443` and the existing TLS listener. Do not use `down`,
remove containers, remove images, or remove volumes. Requests can receive a
short upstream failure while Node and Spring are stopped, but Caddy itself is
not replaced merely to free a port.

```bash
docker compose --profile container-caddy --env-file "$old_env" \
  -f "$old_release/deployment/docker-compose.production.yml" \
  stop spring-api node mysql
```

The deployment bootstrap has an intentional invariant:

| Node state | MySQL state | Result |
| --- | --- | --- |
| running | running | Existing data services are backed up and reused. |
| stopped | stopped | Bootstrap starts both services after images are ready. |
| running | stopped | Deployment stops with a partial-data-services error. |
| stopped | running | Deployment stops with a partial-data-services error. |

Therefore, never stop only Node or only MySQL. Stopping Spring alone avoids the
partial-state error, but is not the conservative low-memory path because it may
not release enough memory for image builds. If Node and MySQL are both stopped,
the new release safely takes the `stopped/stopped` bootstrap path. In container
Caddy mode, leave a running `caddy` service owned by the same Compose project in
place. Execute-time preflight verifies its project/service labels and public
port bindings, while continuing to reject a listener owned by anything else.

Run execute-time preflight from the new release directory before rollout:

```bash
new_release=<new-release-directory>
new_env=<new-environment-file>

bash "$new_release/deployment/scripts/preflight.sh" \
  --env-file "$new_env" \
  --compose-file "$new_release/deployment/docker-compose.production.yml" \
  --execute
```

If memory or any other preflight check fails, do not deploy. Restore the
required host capacity or resolve the reported configuration issue first.

### 5. Roll Out With `release.sh`

Run from the exact extracted release directory. Use `release.sh`, not
`deploy.sh`, for a normal production release. `release.sh` delegates to
`deploy.sh`, verifies loopback health, synchronizes the stable Caddy bind, reloads
the matching Caddy container when `CADDY_MODE=container`, records the active
release, and retains the rollback release only after health passes.

```bash
cd "$new_release"
bash deployment/scripts/release.sh \
  --release <immutable-release-tag> \
  --env-file "$new_env" \
  --private-root <private-resource-root> \
  --backup-root <backup-root> \
  --release-root <release-root> \
  --execute --confirm RELEASE-structify.cn
```

Do not pass `--skip-build` unless the two immutable local release images were
already built and their identities were verified. The standard source archive
path builds them on the host. The release flow runs execute-time preflight
again, starts Node and MySQL together when both were stopped, creates its own
pre-migration backup, starts Spring for Flyway, verifies loopback health, and
records image IDs for rollback.

The first release from the old release-bound Caddyfile layout, or from an
older Caddy with `admin off`, is a controlled exception: after preflight
has verified that the current Compose Caddy owns `80/443`, deployment stops
and removes only that verified container before creating the stable-bind
replacement. Expect a short public listener interruption during this one-time
migration. Do not stop or remove Caddy manually before this gate.

After that migration, a normal release must not recreate Caddy. Execute-time
preflight accepts occupied `80/443` only when the one running container has
the expected Compose project/service labels and actually publishes those
ports. Deployment then atomically synchronizes the stable Caddyfile and calls
the container-local admin endpoint at `127.0.0.1:2019`. Any unrelated port
owner, missing label, extra Caddy container, symlinked stable directory, or
invalid Origin CA mount remains a hard stop.

For a deliberate Caddy recreation after a reviewed CADDY_IMAGE or runtime
change, use the separately confirmed maintenance path from the extracted
release directory. It is not the normal release command:

    bash deployment/scripts/deploy.sh --release <immutable-release-tag> \
      --env-file "$new_env" --private-root <private-resource-root> \
      --backup-root <backup-root> --refresh-caddy --execute \
      --confirm REFRESH-CADDY-structify.cn

## Public Routing And DNS Handoff

The verified production topology is container Caddy. After the new release
reports healthy:

1. Confirm the Compose `caddy` service is running and that its public listener
   is the only owner of `80/443`.
2. Confirm the release Caddyfile has explicit routes for `structify.cn`,
   `www.structify.cn`, and `admin.structify.cn`; do not manually edit the
   running container configuration.
3. Create the proxied `admin -> structify.cn` CNAME at the DNS provider only
   after Caddy is healthy and ready to serve the management hostname.
4. Wait for DNS/TLS visibility, then use the scripted public smoke check.

For a future shared-host deployment, use `CADDY_MODE=host` and the separate
host-Caddy handoff documented in `deployment/README.md`. Do not switch modes
during a release to bypass a port conflict.

```bash
bash "$new_release/deployment/scripts/smoke.sh" \
  --execute \
  --domain https://structify.cn \
  --admin-domain https://admin.structify.cn
```

The smoke command is read-only. It verifies both public roots and health
routes, the public/admin CORS preflights, and that an unsigned administrator
capability request returns `401` rather than `200`, `404`, or an accidental
redirect.

## Acceptance And Stop Conditions

The release is complete only when all of the following are true:

- Upload reports matching local and remote SHA-256 values.
- The old-release backup completed before stopping application services.
- Execute-time preflight passed without weakening its memory or security gates.
- `release.sh` completed and reported healthy services.
- In the current topology, container Caddy owns public `80/443` and routes both
  public hostnames from the reviewed release Caddyfile.
- `admin.structify.cn` resolves through the intended provider path and HTTPS
  smoke checks pass.
- Public administrator endpoints reject unsigned callers with `401`.
- The active-release marker, backup manifest, image identity record, and prior
  release directory remain available for the documented rollback process.

Stop and investigate instead of continuing when a checksum differs, backup
fails, preflight fails, Node/MySQL are only partially running, Flyway fails,
loopback health fails, the selected Caddy topology fails validation, or public
smoke fails. Do not use `git reset`, `git clean`, manual database changes, DNS
guessing, or an ad-hoc Caddy mode switch to bypass those conditions.

## Deliberate Non-Actions

- This runbook does not deploy through DPanel.
- It does not expose or print production environment values.
- It does not connect a model provider, send a model prompt, or manufacture a
  successful provider status.
- It does not execute rollback. Rollback remains a separately authorized,
  image-identity-checked operation.

## Verified Release Record: 2026-08-11

- Active release: `v1.0.16-structify`.
- Source archive SHA-256:
  `282b04b2688f58d4484c23c4d77f9749a96ed6538352aba1dcc9ffa88e9ad466`.
- Rollback release retained by `release.sh`: `v1.0.14-structify`.
- Pre-release and release backups were checksum-verified; the final release
  backup is `20260811T143434Z` under the protected backup root.
- Caddy is mounted from `/srv/structify/caddy`, its admin endpoint is
  container-local, and the public/admin smoke suite passed.
- DNS was not changed during this release. Model and mail integrations remain
  disabled by production capability state; no fake provider or email success
  was reported.
- It does not change `frontend/` or move application source outside
  `backend/node/`, `backend/spring/`, and `frontend/`.
