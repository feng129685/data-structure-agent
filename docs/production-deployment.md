# structify.cn production runbook

This runbook describes a two-backend compatibility deployment. It is written
for a Linux host with Docker Compose v2, a DNS provider, a secret manager, and
an operator who can review backups. It does not contain credentials. The
default production mode is an isolated Structify stack behind an existing host
Caddy site block, so it does not take over unrelated public listeners.

The older `docs/project/13-cloudflare-deployment-guide.md` is a historical
prototype/Cloudflare note and still names `agent.example.com` and placeholder
server state. It is not an approval record for `structify.cn`; use this runbook
and the files under `deployment/` for production.

## 参数来源与路径边界（中文速查）

部署时不要猜占位符，也不要把公网 HTTPS 域名自动当作 SSH 地址。完整的
参数说明和逐步命令在
[`docs/project/14-production-release-runbook.md`](project/14-production-release-runbook.md)
的 **Parameter Sources And Defaults** 小节；本节给出另一个对话最容易缺失的
五个值：

| 值 | 从哪里取得 | 默认值/要求 | 在哪里使用 |
|---|---|---|---|
| SSH 主机名/IP (`-HostName`) | 服务器提供商记录、运维交接记录，或已验证的 Windows SSH alias | 没有默认值；必须先用 `ssh -G` 和已知主机密钥验证 | Windows 上传端连接生产 Linux 主机 |
| SSH 用户 (`-User`) | 主机初始化时创建的运维账号 | 脚本默认 `ubuntu`；必须能 `sudo -n true` 和运行 Docker Compose | Windows SSH 登录 |
| 环境文件 (`--env-file`) | 生产 Linux 主机上的秘密文件，不从仓库复制 | `/etc/structify/structify.env`，真实文件 `0600` | `preflight.sh`、`backup.sh`、`release.sh` 读取；命令只传路径，不打印内容 |
| 私有资源根 (`--private-root`) | 主机上由运维管理的课程/知识/PPT/PDF 目录 | `/srv/structify/private`，与发布目录分离 | 只读挂载；备份时显式传入才会打包私有媒体 |
| 备份根 (`--backup-root`) | 主机上用于恢复的独立磁盘/目录 | `/var/backups/structify`，建议 `0700`，不能放进发布目录 | 保存时间戳备份、`last-release.env`、`previous-release.env` |

当前 Windows 上传器的 `-User` 默认是 `ubuntu`、`-Port` 默认是 `22`、
`-ReleaseRoot` 默认是 `/srv/structify/releases`、`-RemoteUploadRoot` 默认是
`/tmp/structify-upload`。这些远端 Linux 路径不能写成 Windows 路径，也不能把
`/srv/structify/private` 或 `/var/backups/structify` 放进 ZIP。

本次发布已确认的 SSH 连接参数：-HostName 49.232.245.99、-User ubuntu、-Port 22。
初始密码必须从服务器提供商控制台、初始化交接信息或运维管理员处取得；
如果没有密码也没有 SSH 私钥，先通过云厂商控制台/VNC/串口控制台重置 ubuntu
密码或安装 SSH 公钥。-SaveCredential 不能生成或找回密码，只能保存已经取得的
密码。拿到密码后，第一次使用同一台 Windows 电脑时运行
upload-release.ps1 -HostName 49.232.245.99 -User ubuntu -Port 22 -SaveCredential，
在安全提示中输入密码，之后脚本从
%LOCALAPPDATA%\Structify\credentials\production-ssh.xml 读取 DPAPI 凭据。
换 Windows 用户或电脑时必须重新保存；忘记密码只能由服务器提供商或管理员重置。

最小验证顺序如下：先在 Windows 执行上传脚本的 dry-run，再用
`ssh -o StrictHostKeyChecking=yes -p <port> <user>@<host>` 验证登录、
`sudo -n true` 和 `docker compose version`；登录后只检查路径是否存在和权限，
不要 `cat /etc/structify/structify.env`。首次部署由 Linux 主机上的
`init-production-env.sh` 生成环境文件；密码只通过 SSH 密钥或上传脚本的
Windows DPAPI 凭据文件使用，不能写进 PowerShell 命令、文档或 Git。

## Scope and traffic boundary

```text
                         public 80/443
Internet -> structify.cn / admin.structify.cn -> existing host Caddy
                                                 |-- /api/v1/* ----------> Spring loopback :18792
                                                 |-- /api/* --------------> Node loopback :18791
                                                 |-- /presentation/* -----> Node signed/auth route
                             `-- /, /pdfs/* ----------> Node static/legacy route

Spring :8792 -> MySQL 8.4 (private data network)
Node   :8791 -> SQLite volume + private knowledge/PPT volumes (read-only media)
```

`/api/v1/chat/stream` is Spring SSE. Legacy `POST /api/chat` may also stream
when the request asks for a stream. Caddy sets `flush_interval -1` on both
API paths so chunks are not buffered. Spring's current API has no WebSocket
endpoint. Caddy's standard reverse proxy keeps HTTP Upgrade support available
for a future endpoint, but no `/ws` path is advertised or required today.

The `/presentation/*` route is deliberately proxied to Node. Caddy does not
mount or serve `PRESENTATION_DIR`; Node's `presentation-runtime.js` confines
files to its `rendered/` directory and `server.js` requires either a valid JWT
or a non-expired HMAC URL signature. Never replace that route with `file_server`.

## Prerequisites and known blockers

1. Obtain a Linux host, Docker Engine, Compose v2, Caddy-compatible DNS, and
   outbound access to the selected model, SMTP, Judge0/Piston, and ACME servers.
   A shared host must already run Caddy and have a complete Caddyfile that can
   import the reviewed Structify site block. Execute preflight reads Linux
   `MemAvailable`; the default `low-memory` profile has a 1,344 MiB effective
   requirement (1,088 MiB service hard caps plus a 256 MiB host reserve), so a
   host with about 1.4 GiB available can pass only while that budget fits. A
   non-Caddy TLS owner is not a compatible
   handoff without a separately reviewed integration.
2. Create a release tag and record the source revision and image digests. This
     checkout currently has a verified `origin` remote and a recorded
     `origin/main` revision, but this worktree is uncommitted. Create and
     record an immutable release commit/tag and image digests before promotion.
3. Create the private root with permissions readable by the container users:

   ```text
   /srv/structify/private/knowledge/
   /srv/structify/private/course-content/
   /srv/structify/private/presentation-materials/
     slides.json
     lesson-presentation-plans.json
     rendered/<deck>/<page>.png
   /srv/structify/private/pdfs/
     <reviewed-release-pdf>.pdf
   ```

   Do not place OCR, teacher PPT originals, authorization evidence, student
   data, or credentials in the repository. Keep originals and rendered media
   in an access-controlled private store.

4. Create `/etc/structify/structify.env` with
   [`deployment/scripts/init-production-env.sh`](../deployment/scripts/init-production-env.sh).
   The parent directory must be a real Linux `0700` directory; the generator
   creates distinct database and JWT secrets, writes the result with mode
   `0600`, refuses to overwrite an existing file, and never prints values.
   Enable model, SMTP, or remote execution only by adding their real
   secret-manager values after the corresponding service is ready. The file
   must not be copied back into the checkout or included in a support bundle.
   In shared-host mode, set `HOST_CADDY_CONFIG` to the complete active
   Caddyfile which imports `Caddyfile.host.production`, and keep
   `CADDY_MODE=host`, `MEMORY_PROFILE=low-memory`,
   `MIN_AVAILABLE_MEMORY_MB=1024`, and the generated memory limits/reservations
   unless the host is dedicated to Structify.

Compose mounts the external `PDF_SOURCE_DIR_HOST` at `/app/default-pdfs` as
read-only; the Node image creates that mount point but never packages
courseware. On first boot of a new `node-pdfs` volume, the entrypoint copies
non-conflicting source files and creates `.course-pdfs-seeded`. This marker is
independent of the historical `.seeded` marker: an existing volume that has
only the old marker receives the reviewed baseline once, without deleting or
overwriting user uploads. That volume is writable only for the legacy upload
route and is included in the application backup; later restarts do not
overwrite it when the source directory changes. Private PPT, course resources,
and the PDF source remain read-only external mounts.
`Dockerfile.node.dockerignore` also allowlists the build context so local
private directories and SQLite files are not sent to the Docker daemon.

## Required production values

- `CORS_ALLOWED_ORIGINS` must be exactly `https://structify.cn,https://admin.structify.cn`.
- Create the `admin.structify.cn` DNS record only after the reviewed Caddy site
  block is installed and reloaded. In Cloudflare Origin CA mode, the installed
  certificate must include `admin.structify.cn`; do not rely on a certificate
  issued only for the learning host.
- `CADDY_MODE=host` is the default for a shared host. Import
  `deployment/Caddyfile.host.production` into the existing host Caddy config,
  validate the complete Caddy configuration, then reload it. Do not replace
  the existing Caddyfile or start the container Caddy profile on that host.
  `HOST_CADDY_CONFIG` must be the absolute path to that complete config;
  execute preflight invokes `caddy validate` against it before Docker work.
  The source Caddy block routes to `127.0.0.1:18791` and
  `127.0.0.1:18792`; both ports must be unused before deployment.
- `MEMORY_PROFILE=low-memory` is the default. Its 1,024 MiB configured floor
  is based on Linux `MemAvailable`, but preflight also requires the larger of
  `MEMORY_BUDGET_MB` and all declared service hard caps plus
  `MEMORY_RESERVE_MB` (1,344 MiB by default). Do not lower the service limits,
  reservations, or reserve independently to force a host through the gate.
- Set `CADDY_MODE=container` only on a dedicated host. In the default ACME
  path, leave `ORIGIN_CERT_DIR_HOST` empty, set `ACME_EMAIL`, and let the
  profiled Compose Caddy service own public `80/443`. For Cloudflare Full
  (strict) Origin CA mode, set `ORIGIN_CERT_DIR_HOST` to an absolute external
  directory with mode `0700` containing `origin.crt` and an `origin.key` with
  mode `0600`; preflight rejects a missing, unreadable, weak-permissioned, or
  symlinked pair before Docker runs. It also loads the pair through a networkless
  read-only `caddy validate` container using the same `CADDY_IMAGE` that Compose
  will start. Caddy mounts that
  directory read-only, uses the pair for both `structify.cn` and
  `www.structify.cn`, and does not require `ACME_EMAIL`. The certificate and
  key remain outside Git, images, release bundles, and backups sent to source
  control. This container-only option does not modify host-managed Caddy.
- HSTS is intentionally not enabled by the shipped Caddy files. Add it only
  after the domain owner confirms that every current and future subdomain is
  HTTPS-only; do not enable `includeSubDomains` as part of the first rollout.
- `JWT_SECRET` and `NODE_COMPAT_JWT_SECRET` must each be at least 64 random
  characters and must differ. Generate each separately with `openssl rand -hex
  32`. `JWT_SECRET` is confined to Spring. Node uses only
  `NODE_COMPAT_JWT_SECRET`; while `NODE_COMPAT_ENABLED=true`, Spring accepts a
  Node Bearer token only for progress, learning-event, DSVP simulation, and
  owned animation-observation endpoints. It never trusts the Node user ID or
  roles. Existing Spring accounts are mapped by verified email and retain only
  their database roles; a missing account may be mirrored only as `STUDENT`,
  never as teacher or administrator.
- `AUTH_COOKIE_SECURE=true`; Spring emits an `HttpOnly; Secure; SameSite=Strict`
  `ds_session` cookie and accepts the documented Bearer token as well.
- `BOOTSTRAP_ADMIN_EMAIL`, `TEACHER_EMAILS`, and
  `ALLOW_FIRST_USER_TEACHER` remain empty/false. There is no static production
  administrator. Role elevation requires an audited, reviewed database change
  or a future admin workflow; do not solve this by setting the first user as a
  teacher.
- `KNOWLEDGE_DEBUG_API=false`, `VERIFICATION_CODE_FILE` empty, and
  `KNOWLEDGE_AUTO_PUBLISH_LOCAL=false`. A file appearing under the private
  knowledge mount does not publish it to retrieval.
- `PDF_SOURCE_DIR_HOST` is required and must be an absolute host directory
  containing the reviewed release PDFs. It is mounted read-only and must be
  readable by the Node container user. Execute-time preflight verifies that
  the directory exists. Changing its contents does not replace PDFs already
  copied to an existing `node-pdfs` volume after `.course-pdfs-seeded` exists;
  perform an explicit, backup-tested data migration when the baseline PDF set
  must change.
- `AUTH_EXPOSE_DEV_CODE=false` in every public environment. Set
  `AUTH_MAIL_ENABLED=true` only after SMTP delivery is configured and tested.
  With mail disabled, production verification-code requests return
  `SMTP_NOT_CONFIGURED` rather than pretending a message was delivered.
- `MODEL_API_KEY`, `SMTP_PASS`, database passwords, `JWT_SECRET`, and
  `NODE_COMPAT_JWT_SECRET` are secret-manager values only. The model base URL must include the provider's
  documented OpenAI-compatible `/v1` path for the selected provider.
  Model configuration is optional for a degraded rollout; unconfigured model
  requests return a documented unavailable error.
- When mail is enabled, SMTP must use either implicit TLS (`SMTP_PORT=465`,
  `SMTP_SSL=true`, `SMTP_STARTTLS=false`) or required STARTTLS
  (`SMTP_PORT=587`, `SMTP_SSL=false`, `SMTP_STARTTLS=true`,
  `SMTP_STARTTLS_REQUIRED=true`). Keep
  `SMTP_SSL_CHECK_SERVER_IDENTITY=true`.
- `PISTON_BASE_URL` and `JUDGE0_BASE_URL` are optional only when code
  execution is deliberately disabled. When neither is configured, Node returns
  `COMPILER_NOT_CONFIGURED`; when configured, use team-controlled HTTPS
  sandboxes. User code must never execute on this host.

## First rollout

All commands below are examples. The first invocation of every script is a
dry-run. Review output, then add the explicit confirmation flag.

```bash
install -d -m 700 /etc/structify /var/backups/structify
deployment/scripts/init-production-env.sh \
  --output /etc/structify/structify.env \
  --release <immutable-release-tag>

deployment/scripts/preflight.sh \
  --env-file /etc/structify/structify.env

deployment/scripts/deploy.sh \
  --env-file /etc/structify/structify.env \
  --release 2026.08.09-001

deployment/scripts/deploy.sh \
  --env-file /etc/structify/structify.env \
  --private-root /srv/structify/private \
  --release 2026.08.09-001 \
  --execute --confirm DEPLOY-structify.cn
```

Before the execute step, set `NODE_IMAGE=structify-node:<release>` and
`SPRING_IMAGE=structify-spring:<release>` in the environment file. `deploy.sh`
builds those immutable local tags, performs a backup first, starts MySQL, lets
Spring run Flyway migrations, and then starts Node and Spring. In host mode it
does not change Caddy or DNS; validate and reload the imported host site block
only after loopback health passes.

Run local health checks after startup:

```bash
deployment/scripts/health-check.sh --env-file /etc/structify/structify.env
deployment/scripts/health-check.sh --env-file /etc/structify/structify.env --execute
```

The first command prints the plan. The second checks loopback Node `/healthz`,
loopback Spring `/actuator/health`, and Compose service state. Actuator details
are not exposed through Caddy.

## Database migration and legacy SQLite

Spring starts with `SPRING_PROFILES_ACTIVE=prod`, `Flyway.clean-disabled=true`,
`validate-on-migrate=true`, and `baseline-on-migrate=false`. Take and restore-
test a backup before a release. A migration failure must keep the old release
serving while the operator diagnoses the failed container; do not run
`flyway clean` or manually delete migration history.

The DSVP evidence repair does not modify the already published V11 migration:
it uses V11's `dsvp_request_snapshots.animation_record_id` foreign key and the
existing chapter columns. After Flyway validation, the authenticated smoke
fixture must confirm that one source-verified simulation has a non-null linked
animation ID and matching chapter IDs in both the animation and learning rows.
Run that check against a disposable smoke user and remove only that fixture;
never print request payloads, user tokens, or environment values in deployment
logs.

The legacy importer is intentionally disconnected from MySQL. Its default is
a read-only audit:

```bash
deployment/scripts/migrate-sqlite.sh \
  --sqlite /srv/structify/legacy/data.db \
  --target staging
```

Only after a restore-tested staging backup and review may an operator emit a
new staging SQL file:

```bash
deployment/scripts/migrate-sqlite.sh \
  --sqlite /srv/structify/legacy/data.db \
  --target staging \
  --emit-sql /srv/structify/migrations/legacy-staging.sql \
  --execute --confirm MIGRATE-staging
```

The importer rejects production targets and never copies the SQLite database
or its legacy scrypt hashes into MySQL. Review the blocked-row and unmapped-
field report before any staging import. See the data-model difference table in
[`data-model-node-spring-differences.md`](data-model-node-spring-differences.md).

## Backup and restore

The backup contains a transactional MySQL dump, an online SQLite backup, image
metadata, and SHA-256 hashes. Private media is included only when the operator
passes `--private-root`; because it may contain copyrighted course material,
an object-storage or filesystem snapshot is still recommended.

```bash
deployment/scripts/backup.sh \
  --env-file /etc/structify/structify.env \
  --backup-root /var/backups/structify \
  --private-root /srv/structify/private

deployment/scripts/backup.sh \
  --env-file /etc/structify/structify.env \
  --backup-root /var/backups/structify \
  --private-root /srv/structify/private \
  --execute --confirm BACKUP-structify.cn
```

Restore is destructive and first requires an operator-approved maintenance
window and an additional current backup:

```bash
deployment/scripts/restore.sh \
  --env-file /etc/structify/structify.env \
  --backup-dir /var/backups/structify/<timestamp>

deployment/scripts/restore.sh \
  --env-file /etc/structify/structify.env \
  --backup-dir /var/backups/structify/<timestamp> \
  --execute --confirm RESTORE-structify.cn
```

The script verifies `SHA256SUMS`, stops Node/Spring, restores MySQL and the
Node SQLite volume, then restarts the stack. Private media is not implicitly
overwritten; restore it from its separately reviewed snapshot.

## Smoke checks and DNS cutover

Do not switch DNS until the new host passes a local health check and a public
smoke test through the real TLS name. The smoke test is read-only and does not
send a model prompt or log in:

```bash
deployment/scripts/smoke.sh --domain https://structify.cn
deployment/scripts/smoke.sh --domain https://structify.cn --execute \
  --presentation-path /presentation/<known-rendered-image>.png
```

For a new address, validate with `curl --resolve` before changing records.
Lower the DNS TTL in advance, record current A/AAAA/CNAME and proxy settings,
then switch the records manually through the DNS provider. This repository has
no provider credentials or provider API integration. `dns-check.sh` only reads
records:

```bash
deployment/scripts/dns-check.sh --domain structify.cn
deployment/scripts/dns-check.sh --domain structify.cn --expected-ip NEW_IP --execute
```

After the change, repeat the smoke test from a second network and monitor Caddy,
Node, Spring, MySQL, SMTP, model, and Piston error rates for at least one TTL.
Restore the recorded DNS values if the old host is still healthy and rollback
is required.

## Application rollback

`deploy.sh` records the deployed image tags and immutable local image IDs in
`/var/backups/structify/last-release.env`, then preserves the prior values in
`/var/backups/structify/previous-release.env` before each replacement.
`rollback.sh` refuses a mutable tag that no longer resolves to the recorded
image ID. Roll back only to an image whose Flyway schema is compatible with the
current database:

```bash
deployment/scripts/rollback.sh \
  --env-file /etc/structify/structify.env \
  --release-env /var/backups/structify/previous-release.env

deployment/scripts/rollback.sh \
  --env-file /etc/structify/structify.env \
  --release-env /var/backups/structify/previous-release.env \
  --execute --confirm ROLLBACK-structify.cn
```

The rollback changes application images only. It never reverses Flyway or
restores data implicitly. If the prior binary cannot read the current schema,
use the restore-tested backup procedure instead, then redeploy the matching
image and verify health before DNS is restored.

## Release verification checklist

- [ ] Release source revision and image digests are recorded externally.
- [ ] Secret manager values replaced every placeholder; no secret entered Git.
- [ ] CORS is exactly `https://structify.cn`; secure cookie is enabled.
- [ ] Static admin/teacher elevation, debug API, and verification-code capture are off.
- [ ] Private knowledge, course resources, and rendered PPT directories are mounted read-only.
- [ ] `PDF_SOURCE_DIR_HOST` exists, contains the reviewed release PDFs, and is readable by the Node container user; its first-boot seed behavior is understood before rollout.
- [ ] 18791/18792 bind only loopback; MySQL has no host port; only the selected Caddy owner is public.
- [ ] MySQL backup was created and restore-tested; private media snapshot exists.
- [ ] Flyway completed without `clean`; health and smoke checks passed.
- [ ] A source-verified DSVP smoke request linked its snapshot to the animation record and appeared in the expected chapter progress; a context-free preview wrote no evidence.
- [ ] Unsigned `/presentation/*` returns 401/404 and no filesystem path is disclosed.
- [ ] SSE stream headers/chunks are not buffered; no WebSocket endpoint is claimed.
- [ ] DNS cutover and rollback owner, time, and previous records are recorded.

## Private resources excluded from this repository

The public source/artifact allowlist must exclude these exact workspace or
production paths:

| Path/pattern | Reason |
|---|---|
| `.env`, `.env.*` except example templates, `.jwt-secret` | Model, SMTP, database, and JWT secrets. |
| `data.db`, `data.db-wal`, `data.db-shm`, `*.sql`, database dumps | Accounts, messages, learning history, and password hashes. |
| `private/knowledge/**`, `private/course-content/**` | Copyrighted OCR and reviewed private course material. |
| `private/source-ppt/**`, `private/presentation-materials/**` | Original teacher PPT/PPTX files, extracted text/notes, labels, manifests, and rendered slide images. |
| `/srv/structify/private/pdfs/**` | Operator-managed release PDF source; keep copyrighted courseware out of the repository and image build context. |
| `uploads/**`, private `node-pdfs` volume snapshots | User/teacher uploads and potentially personal content. |
| `review-records/**`, license/authorization evidence | Internal review identities and contractual evidence. |
| `/etc/structify/structify.env`, Caddy `/data`, Origin CA certificate directories, backup directories | Live credentials, ACME/Origin CA private keys, database/media backups. |
| Internal Judge0/Piston URLs, server IPs, release image IDs | Infrastructure and release-control metadata. |

Some of these directories and database files are present in this working
checkout and are intentionally ignored or kept outside the image build context.
The verified Git history still contains an older hard-coded credential marker;
rotate/revoke that credential before promotion, run a tracked-file/secret scan,
and create the release from a clean, reviewed commit. Do not publish private
courseware, local databases, rendered output, or environment files.
