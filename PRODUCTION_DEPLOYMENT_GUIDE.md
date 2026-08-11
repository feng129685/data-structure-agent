# Structify 生产部署统一手册

## 文档定位

本文件把以下三个手册合并为一份可直接执行的操作手册：

- deployment/README.md
- docs/production-deployment.md
- docs/project/14-production-release-runbook.md

原文件保留，便于旧链接继续工作；后续生产发布以本文件为主。本文不保存
SSH 密码、生产环境变量内容、数据库密码、模型 API Key、证书私钥或备份数据。

当前生产拓扑是专用主机上的 Compose Caddy 模式。共享主机模式仍被脚本支持，
但必须单独完成 Caddy 交接，不得在发布过程中临时切换模式来绕过端口冲突。

## 目录

1. [当前发布参数](#当前发布参数)
2. [职责和安全边界](#职责和安全边界)
3. [生产拓扑和目录](#生产拓扑和目录)
4. [发布前置条件](#发布前置条件)
5. [密码和 SSH 认证](#密码和-ssh-认证)
6. [完整发布流程](#完整发布流程)
7. [备份、恢复和回滚](#备份恢复和回滚)
8. [迁移和数据注意事项](#迁移和数据注意事项)
9. [验收和停止条件](#验收和停止条件)
10. [脚本索引](#脚本索引)

## 当前发布参数

以下是本次已确认的连接参数。若目标主机发生变化，必须先更新本节和命令变量，
再运行任何上传命令。

| 参数 | 当前值或默认值 | 说明 |
|---|---|---|
| SSH 主机名/IP | 49.232.245.99 | 来自运维提供的 SSH 地址；不要从 HTTPS 域名猜测 |
| SSH 用户 | ubuntu | upload-release.ps1 默认值；必须能执行 sudo -n true 和 Docker Compose |
| SSH 端口 | 22 | 脚本默认值；非 22 端口必须显式传入 -Port |
| 本地发布包 | D:\Desktop\Study\数据结构智能体\release-artifacts\structify-v1.0.18-structify.zip | Windows 本机路径，不是服务器路径 |
| 发布标签 | v1.0.18-structify | 不可变标签，不能使用 latest |
| 生产环境文件 | /etc/structify/structify.env | Linux 主机上的真实文件，权限 0600 |
| 私有资源根 | /srv/structify/private | Linux 主机上的只读课程、知识、PPT、PDF 资源 |
| 备份根 | /var/backups/structify | Linux 主机上的恢复目录，建议权限 0700 |
| 发布根 | /srv/structify/releases | 每个不可变源码发布目录的父目录 |
| 临时上传根 | /tmp/structify-upload | SCP 暂存目录，默认上传完成后清理 |
| Caddy 稳定配置目录 | /srv/structify/caddy | container 模式下必须位于版本发布目录之外 |

本次发布使用的镜像名必须是：

~~~text
NODE_IMAGE=structify-node:v1.0.18-structify
SPRING_IMAGE=structify-spring:v1.0.18-structify
~~~

## 职责和安全边界

三个入口的职责不同，不能互相替代：

| 脚本 | 所在机器 | 负责什么 | 不负责什么 |
|---|---|---|---|
| deployment/scripts/upload-release.ps1 | Windows | 校验 ZIP、SCP 上传、远端 SHA-256 校验、原子解压 | 不读生产环境、不运行 Compose、不改 Caddy、不改 DNS |
| deployment/scripts/preflight.sh | Linux | 校验环境文件、路径、端口、Caddy、内存和 Compose 配置 | 不启动服务、不创建发布 |
| deployment/scripts/release.sh | Linux | 唯一正常生产发布入口，构建镜像、备份、Flyway、启动服务、健康检查、保留回滚版本 | 不改 DNS、不回滚数据库、不覆盖私有资源 |

所有变更脚本默认 dry-run。真正产生变更必须同时提供 --execute 和精确的
--confirm 值：

~~~text
UPLOAD-structify.cn
BACKUP-structify.cn
DEPLOY-structify.cn
RELEASE-structify.cn
REFRESH-CADDY-structify.cn
RESTORE-structify.cn
ROLLBACK-structify.cn
~~~

不要使用 DPanel 替代脚本，不要从工作树直接发布，不要使用 git reset、git clean、
手工数据库修改、关闭 StrictHostKeyChecking 或临时切换 Caddy 模式来绕过门禁。

## 生产拓扑和目录

公网流量路径：

~~~text
Internet
  -> structify.cn / admin.structify.cn
  -> Compose Caddy :80/:443
     /api/v1/*       -> Spring 127.0.0.1:18792
     /api/*          -> Node   127.0.0.1:18791
     /presentation/* -> Node signed/auth route
     / and /pdfs/*   -> Node static/legacy route

Spring -> MySQL 8.4 on the private Compose network
Node   -> SQLite volume and read-only private media mounts
~~~

Spring SSE 路径是 /api/v1/chat/stream。当前没有 WebSocket endpoint，也没有
/ws 路径。Caddy 对 API 路径使用 flush_interval -1，避免 SSE 被缓冲。
/presentation/* 必须经过 Node 的 JWT 或 HMAC 校验，不能改成 Caddy file_server。

### Linux 主机目录

私有资源根不是发布根，也不是备份根：

~~~text
/srv/structify/private/knowledge/
/srv/structify/private/course-content/
/srv/structify/private/presentation-materials/
  slides.json
  lesson-presentation-plans.json
  rendered/<deck>/<page>.png
/srv/structify/private/pdfs/
  <reviewed-release-pdf>.pdf
~~~

生产环境文件只存在 Linux 主机：

~~~text
/etc/structify/structify.env
~~~

备份根包含时间戳目录、manifest.txt、SHA256SUMS、last-release.env 和
previous-release.env。它必须位于 /srv/structify/releases 之外，并使用适合恢复的
独立存储。

不要把 OCR、教师 PPT 原件、授权证据、学生数据、数据库文件、环境文件或凭据
放进仓库、发布 ZIP 或 Docker build context。

### Caddy 模式

- 当前生产使用 CADDY_MODE=container，专用 Compose Caddy 独占公网 80/443。
- container 模式使用稳定目录 /srv/structify/caddy，并将它挂载到容器的 /etc/caddy。
- ORIGIN_CERT_DIR_HOST 若启用，目录必须是真实目录、权限 0700，origin.key 必须是
  0600；证书和私钥永远留在主机外部。
- 共享主机才使用 CADDY_MODE=host；必须配置 HOST_CADDY_CONFIG，并让现有 Caddy
  导入 deployment/Caddyfile.host.production。不得在共享主机同时启动 container Caddy。

## 发布前置条件

发布前必须具备：

1. Linux 主机、Docker Engine、Compose v2，以及经验证的 Caddy/DNS 路径。
2. 已审核的源修订、不可变发布标签和发布包 SHA-256。
3. Windows known_hosts 中已有目标主机密钥；上传脚本强制
   StrictHostKeyChecking=yes。
4. 生产环境文件由秘密管理流程维护，真实文件权限为 0600，不能复制回仓库。
5. 私有资源目录存在且能被 Node 容器用户读取；PPT、课程资源和 PDF 源以只读方式挂载。
6. 低内存 profile 的可用内存门禁通过。默认服务硬上限为 1088 MiB，加上 256 MiB
   主机 reserve，实际有效预算至少约 1344 MiB。不能降低门禁来强行发布。
7. 需要邮件、模型或代码执行时，先完成对应外部服务配置；未配置时应用应明确返回
   不可用状态，而不是伪造成功。

### 生成环境文件

首次部署时在 Linux 主机运行。脚本拒绝覆盖已有文件，会生成独立数据库和 JWT 密钥，
并将文件写为 0600：

~~~bash
sudo install -d -m 700 /etc/structify /var/backups/structify
sudo bash /srv/structify/releases/<release>/deployment/scripts/init-production-env.sh \
  --output /etc/structify/structify.env \
  --release <immutable-release-tag>
~~~

已有环境文件不得重新生成。只更新审核过的非秘密发布值，例如 NODE_IMAGE 和
SPRING_IMAGE；秘密、Compose 项目名、持久化路径和 Caddy 证书引用保持一致。

### 关键生产值

- CORS_ALLOWED_ORIGINS 必须严格为
  https://structify.cn,https://admin.structify.cn。
- JWT_SECRET 和 NODE_COMPAT_JWT_SECRET 都至少 64 个随机字符，且必须不同。
- AUTH_COOKIE_SECURE=true，AUTH_EXPOSE_DEV_CODE=false。
- BOOTSTRAP_ADMIN_EMAIL、TEACHER_EMAILS 为空，ALLOW_FIRST_USER_TEACHER=false。
- KNOWLEDGE_DEBUG_API=false，VERIFICATION_CODE_FILE 为空，
  KNOWLEDGE_AUTO_PUBLISH_LOCAL=false。
- PDF_SOURCE_DIR_HOST 必须是绝对 Linux 路径，存在、可读，并包含审核过的 PDF。
- AUTH_MAIL_ENABLED=true 之前必须完成 SMTP 交付测试。
- MODEL_API_KEY、SMTP_PASS、数据库密码和 JWT 密钥只来自秘密管理器。
- PISTON_BASE_URL 和 JUDGE0_BASE_URL 为空时，代码执行能力应保持禁用。

## 密码和 SSH 认证

先区分两个动作：

1. 获取初始密码：从服务器提供商的控制台/初始化交接信息、云厂商的重置页面，
   或负责该主机的运维管理员处取得。这个动作发生在部署脚本之外。
2. 保存已有密码：拿到密码后，才在将要执行上传的 Windows 账户上运行
   -SaveCredential，把它转换成当前 Windows 账户可用的 DPAPI 凭据。

-SaveCredential 不能生成、查询或找回密码；它也不会读取服务器环境文件、Git 或
ZIP。如果你既没有初始密码，也没有可用 SSH 私钥，不能继续上传，必须先通过云厂商
网页控制台、VNC/串口控制台或管理员完成重置/密钥配置。通过主机控制台重置时，
由管理员在主机上执行 passwd ubuntu，或安装审核过的 SSH 公钥；不要把新密码写进
仓库或聊天记录。某些镜像会关闭 SSH 密码登录，此时应直接使用 SSH 私钥。

拿到密码后，在本机运行：

~~~powershell
.\deployment\scripts\upload-release.ps1 -HostName 49.232.245.99 -User ubuntu -Port 22 -SaveCredential
~~~

脚本会弹出安全输入提示。输入 SSH 密码后，凭据保存到：

~~~text
%LOCALAPPDATA%\Structify\credentials\production-ssh.xml
~~~

后续上传会通过 SSH_ASKPASS 自动读取 DPAPI 凭据，不接受明文密码参数，也不打印
密码。该文件绑定创建它的 Windows 用户和电脑；换账户、换电脑或文件损坏时，重新
执行 -SaveCredential。忘记密码、账号锁定或认证失败时，只能通过服务器提供商或
管理员重置。不要用 Import-Clixml、日志、截图或命令历史导出密码。

SSH 私钥可替代密码流程。使用 -IdentityFile 时，私钥也不能加入仓库、发布包或
聊天记录。

## 完整发布流程

### 1. Windows 变量和主机预检查

从项目根目录打开 PowerShell，设置本次发布变量：

~~~powershell
$repo = "D:\Desktop\Study\数据结构智能体\data-structure-agent-main"
$artifact = "D:\Desktop\Study\数据结构智能体\release-artifacts\structify-v1.0.18-structify.zip"
$release = "v1.0.18-structify"
$hostName = "49.232.245.99"
$user = "ubuntu"
$port = 22
Set-Location $repo
~~~

如果使用 SSH alias，先确认它解析到正确主机：

~~~powershell
ssh -G $hostName | Select-String '^(hostname|user|port) '
~~~

确认 known_hosts 和权限，不打印环境文件：

~~~powershell
ssh -o StrictHostKeyChecking=yes -o ConnectTimeout=10 -p $port "$user@$hostName" 'printf "ssh-ok host=%s user=%s\n" "$(hostname)" "$(id -un)"; sudo -n true; docker compose version'
~~~

### 2. 校验并上传不可变源码包

先 dry-run。该命令只检查本地 ZIP、计算 SHA-256 并打印计划，不连接服务器：

~~~powershell
.\deployment\scripts\upload-release.ps1 -Artifact $artifact -Release $release -HostName $hostName -User $user -Port $port
~~~

确认归档包含 backend/node/server.js、backend/spring/pom.xml、
deployment/docker-compose.production.yml 和 deployment/scripts/release.sh 后，
执行正式上传：

~~~powershell
.\deployment\scripts\upload-release.ps1 -Artifact $artifact -Release $release -HostName $hostName -User $user -Port $port -Execute -Confirm UPLOAD-structify.cn
~~~

上传器会在远端校验 SHA-256，并原子解压到：

~~~text
/srv/structify/releases/<release>
~~~

校验不一致、目标目录已存在或归档包含私有运行时文件时立即停止。

### 3. 检查远端路径

只查看存在性、权限和所有者，不要 cat 环境文件：

~~~bash
sudo sh -c '
  for path in /etc/structify/structify.env /srv/structify/private \
              /var/backups/structify /srv/structify/releases; do
    if [ -e "$path" ]; then
      stat -c "%a %U:%G %F %n" "$path"
    else
      printf "MISSING %s\n" "$path"
    fi
  done
'
~~~

首次部署按前置条件生成环境文件；已有部署只在审核后更新镜像标签。

### 4. 通过旧版本完成发布前备份

必须使用当前运行版本的 Compose 文件和环境文件。先看计划，再执行：

~~~bash
old_release=/srv/structify/releases/<old-release>
old_env=/etc/structify/structify.env
private_root=/srv/structify/private
backup_root=/var/backups/structify

sudo bash "$old_release/deployment/scripts/backup.sh" \
  --env-file "$old_env" --backup-root "$backup_root" --private-root "$private_root"

sudo bash "$old_release/deployment/scripts/backup.sh" \
  --env-file "$old_env" --backup-root "$backup_root" --private-root "$private_root" \
  --execute --confirm BACKUP-structify.cn
~~~

确认新时间戳目录包含 manifest.txt 和 SHA256SUMS。备份失败或不完整时停止。

### 5. 低内存主机的服务停止顺序

只有在备份成功后，才可用旧 Compose 文件停止 spring-api、node、mysql，以释放
构建内存。保留同一 Compose 项目的 caddy 服务运行，继续占用 80/443：

~~~bash
sudo docker compose --env-file "$old_env" \
  -f "$old_release/deployment/docker-compose.production.yml" \
  stop spring-api node mysql
~~~

不能只停止 Node 或只停止 MySQL，否则 deploy.sh 会拒绝部分数据服务状态。

### 6. 执行 preflight

从新发布目录执行，必须使用新环境文件和新 Compose 文件：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/preflight.sh \
  --env-file /etc/structify/structify.env \
  --compose-file /srv/structify/releases/<release>/deployment/docker-compose.production.yml \
  --execute
~~~

preflight 必须通过环境文件权限、CORS、密钥长度、私有路径、PDF 路径、内存、
Docker Compose、Caddy 证书和公网端口归属检查。失败时不要降低门禁。

### 7. 使用 release.sh 正式发布

正常发布只使用 release.sh，不直接调用 deploy.sh。先 dry-run：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/release.sh \
  --release <immutable-release-tag> \
  --env-file /etc/structify/structify.env \
  --private-root /srv/structify/private \
  --backup-root /var/backups/structify \
  --release-root /srv/structify/releases
~~~

检查计划后，使用唯一正式确认字符串：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/release.sh \
  --release <immutable-release-tag> \
  --env-file /etc/structify/structify.env \
  --private-root /srv/structify/private \
  --backup-root /var/backups/structify \
  --release-root /srv/structify/releases \
  --execute --confirm RELEASE-structify.cn
~~~

该流程会再次 preflight、构建 Node/Spring 不可变镜像、创建迁移前备份、启动
MySQL 和 Node、让 Spring 执行 Flyway、重载稳定 Caddy 配置、检查回环健康，并
记录 last-release.env。除非镜像已单独核验，否则不要传 --skip-build。

### 8. 健康检查和公网 smoke

回环检查：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/health-check.sh \
  --env-file /etc/structify/structify.env
sudo bash /srv/structify/releases/<release>/deployment/scripts/health-check.sh \
  --env-file /etc/structify/structify.env --execute
~~~

公网只读检查：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/smoke.sh \
  --execute --domain https://structify.cn --admin-domain https://admin.structify.cn
~~~

smoke 会检查两个公网根、健康路由、CORS preflight，以及未授权管理员能力请求
是否返回 401。它不会登录、发送模型请求或修改数据。

### 9. DNS 交接

只有本地主机健康、Caddy 路由正确、公网 smoke 通过后，才由 DNS 负责人操作记录：

1. 确认 Caddy 是 80/443 唯一监听者。
2. 确认 Caddyfile 显式包含 structify.cn、www.structify.cn、admin.structify.cn。
3. 再创建或切换 admin -> structify.cn 的代理 CNAME。
4. 用 dns-check.sh 只读确认解析，再从第二网络重复 smoke。

仓库没有 DNS provider 凭据，dns-check.sh 永远不会修改 DNS。

## 备份、恢复和回滚

### 备份内容

backup.sh 会生成 MySQL 事务 dump、Node SQLite 在线备份、Node PDF 卷备份、镜像
元数据和 SHA-256。只有显式传 --private-root 才会打包私有媒体；私有媒体仍建议
使用独立对象存储或文件系统快照。

### 恢复

恢复是破坏性操作，必须有维护窗口和一份更新的当前备份：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/restore.sh \
  --env-file /etc/structify/structify.env \
  --backup-dir /var/backups/structify/<timestamp>

sudo bash /srv/structify/releases/<release>/deployment/scripts/restore.sh \
  --env-file /etc/structify/structify.env \
  --backup-dir /var/backups/structify/<timestamp> \
  --execute --confirm RESTORE-structify.cn
~~~

脚本会校验 SHA256SUMS、停止 Node/Spring、恢复 MySQL 和 Node SQLite 卷，然后
重启服务。私有媒体不会被隐式覆盖，必须从独立快照恢复。

### 应用回滚

deploy.sh 会在备份根记录 last-release.env，并将旧记录保存为
previous-release.env。rollback.sh 只允许回滚到记录过镜像 ID 的不可变镜像：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/rollback.sh \
  --env-file /etc/structify/structify.env \
  --release-env /var/backups/structify/previous-release.env

sudo bash /srv/structify/releases/<release>/deployment/scripts/rollback.sh \
  --env-file /etc/structify/structify.env \
  --release-env /var/backups/structify/previous-release.env \
  --execute --confirm ROLLBACK-structify.cn
~~~

回滚只替换应用镜像，不反向执行 Flyway，也不自动恢复数据。数据库 schema 不兼容
时必须使用恢复流程，而不是硬切旧镜像。

## 迁移和数据注意事项

Spring 使用 prod profile、Flyway clean-disabled=true、validate-on-migrate=true、
baseline-on-migrate=false。迁移失败时保持旧版本可诊断，不运行 flyway clean，
不手工删除 migration history。

DSVP 证据修复使用现有 V11 migration 的外键和章节字段；发布后应使用一次性 smoke
用户确认源验证模拟写入 animation、snapshot、event 和学习章节关联。不要在日志中
打印请求体、token 或环境值。

legacy SQLite 导入默认只读审计，禁止生产目标：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/migrate-sqlite.sh \
  --sqlite /srv/structify/legacy/data.db --target staging
~~~

只有完成恢复测试和审核后，才允许生成 staging SQL：

~~~bash
sudo bash /srv/structify/releases/<release>/deployment/scripts/migrate-sqlite.sh \
  --sqlite /srv/structify/legacy/data.db --target staging \
  --emit-sql /srv/structify/migrations/legacy-staging.sql \
  --execute --confirm MIGRATE-staging
~~~

该导入器不会把 SQLite、legacy scrypt hash 或未映射字段复制进生产 MySQL。

## 验收和停止条件

发布完成必须同时满足：

- 本地和远端 SHA-256 一致。
- 旧版本备份在停止服务前完成，且 manifest 和 SHA256SUMS 存在。
- execute-time preflight 通过，未降低内存或安全门禁。
- release.sh 报告服务健康，Flyway 无 clean。
- Node/Spring 只绑定回环端口 18791/18792，MySQL 无公网端口。
- 当前 Caddy 是 80/443 唯一所有者，并路由两个正式域名。
- 公网 smoke 通过，未授权管理员 API 返回 401。
- active-release、last-release.env、previous-release.env、镜像 ID 和旧发布目录仍可用。

出现以下任一情况立即停止：校验和不一致、备份失败、preflight 失败、Node/MySQL
部分运行、Flyway 失败、回环健康失败、Caddy 拓扑不匹配、端口被非预期进程占用、
公网 smoke 失败或环境文件权限不正确。

## 私有资源和发布包排除项

本手册是受控的内部运维文档，因此前面的当前发布参数可以记录本次已确认的 SSH
地址。公共源码、发布 ZIP 和镜像 build context 仍必须排除服务器 IP、内部服务
地址和其他基础设施元数据：

~~~text
.env、.env.*（示例模板除外）、.jwt-secret
data.db、data.db-wal、data.db-shm、*.sql、数据库 dump
private/knowledge/**、private/course-content/**
private/source-ppt/**、private/presentation-materials/**
uploads/**、node-pdfs 卷快照、review-records/**
/etc/structify/structify.env、Caddy /data、Origin CA 目录、备份目录
内部 Judge0/Piston URL、服务器 IP、镜像 ID 和证书私钥
~~~

## 脚本索引

| 脚本 | 用途 |
|---|---|
| upload-release.ps1 | Windows SCP 上传、SHA-256 和原子解压 |
| init-production-env.sh | Linux 外部秘密文件生成器，拒绝覆盖 |
| preflight.sh | 环境、路径、Caddy、内存和 Compose 门禁 |
| release.sh | 正常生产发布唯一入口 |
| deploy.sh | release.sh 委托的构建/备份/启动逻辑；不要绕过 release.sh |
| backup.sh / restore.sh | MySQL、SQLite、PDF 和可选私有媒体备份/恢复 |
| health-check.sh / smoke.sh | 回环和公网只读检查 |
| dns-check.sh | DNS 可见性检查，不修改记录 |
| rollback.sh | 带镜像身份校验的应用回滚 |
| migrate-sqlite.sh | 只读 legacy 审计和 staging SQL 生成 |

所有脚本的帮助信息以发布包中的实际版本为准；命令参数或确认字符串发生变化时，
必须先同步本手册，再执行发布。

## 本次发布记录

本次发布使用 v1.0.18-structify，上传包 SHA-256 为：

~~~text
83ac03b70945b8d8aa9a6a49396227a124fe018a2728760b5767ef3e90a9b03f
~~~

部署后公网 smoke、回环健康、Caddy reload、镜像记录和回滚保留均已通过。密码没有
写入本文档或仓库。
