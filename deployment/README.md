# Production deployment assets

The production targets are `https://structify.cn` for learning and
`https://admin.structify.cn` for management. The default topology keeps an
existing host Caddy in control of public TLS and routes both hosts to dedicated
loopback ports:

```text
Internet :443
    -> existing host Caddy (structify.cn and admin.structify.cn)
       /api/v1/*       -> Spring loopback :18792 (MySQL, Flyway)
       /api/*          -> Node loopback :18791 (compatibility service)
       /presentation/* -> Node loopback :18791 (JWT/HMAC check)
       / and static    -> Node loopback :18791
```

`CADDY_MODE=host` is the production default. It starts only MySQL, Node, and
Spring, then requires the operator to import and validate
[`Caddyfile.host.production`](Caddyfile.host.production) in the existing host
Caddy configuration. Structify never binds public `80/443` in that mode. The
`container` mode is available only for a dedicated host and explicitly starts
the profiled Caddy service. Node and Spring bind the configurable loopback
ports `18791` and `18792`; MySQL has no host port and is reachable only on the
internal Compose network.

Container Caddy binds a stable host directory, CADDY_CONFIG_DIR_HOST
(default /srv/structify/caddy), at /etc/caddy. It must remain outside
versioned release directories. During a normal repeat deployment, deploy.sh
confirms the existing container belongs to the same Compose project,
atomically replaces that directory's Caddyfile, and reloads it in place. It
does not run compose up caddy, so the current container retains 80/443.
Before any Caddy container is created, deploy.sh also creates and validates
the real `origin-ca` subdirectory below the stable bind. Docker needs that
mountpoint to exist before it can overlay `/etc/caddy/origin-ca` inside the
read-only `/etc/caddy` bind.
The Caddy admin endpoint is bound only to 127.0.0.1:2019 inside the
container; Compose does not publish it.

The first release after adopting this layout detects the old release-bound
mount, or an older Caddy with admin off, and performs one controlled
stop/remove/create sequence only after execute-time preflight has confirmed
the existing Compose Caddy owns the public ports. That brief migration has an
intentional listener interruption. An operator can intentionally recreate an
otherwise healthy stable Caddy, for example after changing CADDY_IMAGE, with
deploy.sh --refresh-caddy --execute --confirm REFRESH-CADDY-structify.cn;
normal releases must not use this maintenance path.

For a Cloudflare-proxied dedicated host, container Caddy supports either ACME
or an operator-managed Cloudflare Origin CA certificate. Leave
`ORIGIN_CERT_DIR_HOST` empty to keep ACME and provide `ACME_EMAIL`. To select
Origin CA mode, set `ORIGIN_CERT_DIR_HOST` to an absolute, non-symlinked host
directory with mode `0700`. It must contain `origin.crt` and an `origin.key`
with mode `0600`; execute preflight verifies the pair and runs a networkless,
read-only `caddy validate` using the same `CADDY_IMAGE` and mounts before
Compose starts. Compose mounts the directory read-only at
`/etc/caddy/origin-ca`, and Caddy uses the pair for `structify.cn`,
`www.structify.cn`, and `admin.structify.cn`. Keep the certificate directory
outside the repository and release bundle; the Origin CA certificate must
include all three names. In Origin
CA mode, `ACME_EMAIL` may be empty. This option applies only to container
Caddy, not a separate host-managed Caddy installation.

Host mode is an execute-time handoff, not merely a port choice. Set
`HOST_CADDY_CONFIG` to the complete existing Caddyfile that imports
`Caddyfile.host.production`; `preflight.sh --execute` runs `caddy validate`
against that file before it invokes Docker. The default `low-memory` profile
reads Linux `MemAvailable` and requires the larger of its configured budget and
the 1,088 MiB service hard-cap total plus its 256 MiB host reserve (1,344 MiB).
A host with another TLS owner must use an explicitly reviewed proxy integration
or a dedicated Structify host; do not start the container-Caddy profile alongside it.

The default build bases are the official Node 22 Bookworm and Eclipse Temurin
21 images. When Docker Hub is unavailable, an operator may set
`NODE_BASE_IMAGE`, `JAVA_BUILD_IMAGE`, and `JAVA_RUNTIME_IMAGE` in the private
production environment file to a verified compatible mirror. Record the
resolved digests with the release; do not add mirror credentials to source.

PDF courseware stays outside the image build context. Set
`PDF_SOURCE_DIR_HOST` to the absolute host directory that contains the
reviewed PDFs for a release; Compose mounts it read-only at
`/app/default-pdfs`. On the first Node boot for a new `node-pdfs` volume, the
entrypoint copies non-conflicting source files into the writable volume and
creates `.course-pdfs-seeded`. This marker is independent of the historical
`.seeded` marker, so an existing volume receives the course-PDF baseline once
when this source is introduced without overwriting user uploads. Later
restarts never overwrite uploads or operator-managed files in that volume, so
updating the source directory alone does not refresh an existing deployment.
Ensure the source directory and its files are readable by the Node container
user.

Use [`../docs/production-deployment.md`](../docs/production-deployment.md) as
the operator runbook. All operational scripts are under `scripts/` and are
dry-run by default. Mutating actions require both `--execute` and an exact
domain-specific `--confirm` value.

Files:

- `docker-compose.production.yml` - Node, Spring, MySQL, and optional profiled Caddy topology.
- `Dockerfile.node` / `Dockerfile.node.dockerignore` / `node-entrypoint.sh` - non-root Node compatibility image; the build context allowlist excludes private media and databases, while the read-only PDF source is seeded once into a dedicated writable upload volume.
- `Caddyfile.production` - dedicated-host container Caddy routes and SSE flush behavior for the learning and management hosts.
- `Caddyfile.host.production` - append-only shared-host site blocks for `structify.cn` and `admin.structify.cn`.
- `.env.spring.example` - placeholder-only production environment template.
- `scripts/init-production-env.sh` - Linux-only secret-file generator; it
  writes fresh database/JWT values outside the checkout, refuses overwrite,
  and leaves optional model/SMTP/sandbox integrations disabled.
- `scripts/preflight.sh` - local configuration and path checks.
- `scripts/upload-release.ps1` - Windows-side SCP uploader with local/remote
  SHA-256 verification, traversal-safe extraction, strict known-host checking,
  and optional DPAPI-protected password authentication. It stages source only;
  it never runs the production rollout.
- `scripts/deploy.sh` - build, backup, Flyway-on-start, and service rollout.
- `scripts/backup.sh` / `restore.sh` - MySQL, SQLite, and optional private-media snapshots.
- `scripts/migrate-sqlite.sh` - read-only legacy audit and staging-only SQL generation.
- `scripts/health-check.sh` / `smoke.sh` - loopback and public read-only checks.
- `scripts/dns-check.sh` - DNS visibility check; it never changes DNS records.
- `scripts/rollback.sh` - image-only rollback with explicit confirmation.

This workspace has a verified `origin/main` source reference, but the current
integration changes are intentionally uncommitted. Do not promote an image
until the release operator creates and records an immutable release commit/tag,
source revision, and artifact digest, and confirms that public-history and
private-courseware checks have passed.

The Spring rollout reuses Flyway V11 for DSVP evidence. Release verification
must exercise both modes: a context-free request returns a non-persisted
preview, while an authenticated source-verified request commits one linked
animation/snapshot/event evidence unit with a non-null chapter. Do this only
after a restore-tested backup and before DNS promotion.

The legacy Node login bridge uses a dedicated `NODE_COMPAT_JWT_SECRET`; it is
not the Spring `JWT_SECRET`. In host or container mode, Node receives only the
compatibility key. Spring accepts it only when `NODE_COMPAT_ENABLED=true` and
only on the restricted learning/animation evidence endpoints documented in the
API difference matrix.
