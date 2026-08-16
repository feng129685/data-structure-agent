# Production deployment assets

## Canonical runbook

完整的生产发布流程只有一份：
[`../PRODUCTION_DEPLOYMENT_GUIDE.md`](../PRODUCTION_DEPLOYMENT_GUIDE.md)。本文件是
兼容入口和部署资产索引，不再维护第二套发布命令。

## Current connection

- SSH host: `49.232.245.99`
- SSH user: `ubuntu`
- SSH port: `22`
- Production environment: `/etc/structify/structify.env` (`0600`)
- Private resources: `/srv/structify/private`
- Backup root: `/var/backups/structify` (`0700` recommended)
- Release root: `/srv/structify/releases`

## One-time SSH credential

密码只需在执行上传的 Windows 账户上保存一次：

~~~powershell
.\deployment\scripts\upload-release.ps1 -HostName 49.232.245.99 -User ubuntu -Port 22 -SaveCredential
~~~

命令会弹出安全输入框；按实际密码原样输入（包括末尾句点，如有）。凭据以 Windows
DPAPI 形式保存到 `%LOCALAPPDATA%\Structify\credentials\production-ssh.xml`，后续
上传自动读取。密码不是 `-Artifact`、`-Release` 或 `-HostName` 参数，也不会从仓库、
ZIP 或生产环境文件读取。完全没有密码时，先通过云厂商控制台/VNC/串口控制台或运维
管理员重置 `ubuntu` 密码或安装 SSH 公钥；脚本不能生成或找回未知密码。

## Asset index

| Asset | Purpose |
|---|---|
| `docker-compose.production.yml` | Node、Spring、MySQL 和当前 container Caddy 拓扑 |
| `Caddyfile.production` | 专用主机 Caddy 路由和 SSE 刷新策略 |
| `Caddyfile.host.production` | 共享主机模式的显式 Caddy 交接配置 |
| `Dockerfile.node` / `node-entrypoint.sh` | 非 root Node 镜像和 PDF 一次性种子 |
| `scripts/upload-release.ps1` | Windows 校验、SCP 上传、远端 SHA-256 和 DPAPI 凭据读取 |
| `scripts/preflight.sh` | Linux 环境、路径、内存、端口和 Caddy 门禁 |
| `scripts/release.sh` | 唯一正常生产发布入口，委托构建、备份、迁移和健康检查 |
| `scripts/backup.sh` / `restore.sh` | 备份和恢复 |
| `scripts/health-check.sh` / `smoke.sh` | 回环和公网只读检查 |
| `scripts/dns-check.sh` | DNS 可见性检查，不修改 DNS |
| `scripts/rollback.sh` | 带镜像身份校验的应用回滚 |

所有变更脚本默认 dry-run；只有根手册列出的 `--execute` 和精确 `--confirm` 同时出现
时才会产生变更。不要用 DPanel、工作树、`git reset`、`git clean` 或临时 Caddy 模式
切换绕过门禁。
