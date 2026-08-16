# structify.cn production runbook (compatibility entry)

完整、可执行的生产手册已经合并到项目根目录：
[`../PRODUCTION_DEPLOYMENT_GUIDE.md`](../PRODUCTION_DEPLOYMENT_GUIDE.md)。本文件保留
原路径，供旧链接和工具引用；不再维护独立的上传、发布、备份或回滚命令。

## Confirmed connection values

| Value | Current value |
|---|---|
| SSH host (`-HostName`) | `49.232.245.99` |
| SSH user (`-User`) | `ubuntu` |
| SSH port (`-Port`) | `22` |
| Production env (`--env-file`) | `/etc/structify/structify.env` (`0600`) |
| Private resource root (`--private-root`) | `/srv/structify/private` |
| Backup root (`--backup-root`) | `/var/backups/structify` (`0700` recommended) |
| Release root (`--release-root`) | `/srv/structify/releases` |

## Credential boundary

首次使用时，在 Windows 上执行以下命令并在安全提示中输入已有密码：

~~~powershell
.\deployment\scripts\upload-release.ps1 -HostName 49.232.245.99 -User ubuntu -Port 22 -SaveCredential
~~~

脚本把凭据保存在 `%LOCALAPPDATA%\Structify\credentials\production-ssh.xml` 并由 DPAPI
保护，后续上传自动读取。密码不放进参数、PowerShell 变量、Git、ZIP、生产环境文件或
本文档。没有密码且没有 SSH 私钥时，先通过云厂商控制台或运维管理员重置账号或安装
公钥；软件无法推断未知密码。

## Non-negotiable boundaries

- 当前生产拓扑以根手册记录的专用主机 container Caddy 为准；不要按旧的 host-Caddy
  默认说明操作。
- 正常发布只从 `release.sh` 进入；不要直接把 `deploy.sh` 当作操作入口。
- `CORS_ALLOWED_ORIGINS` 必须同时包含 `https://structify.cn,https://admin.structify.cn`。
- 私有资源、环境文件、数据库、备份、证书和 PDF 课件必须留在仓库和发布包之外。
- 上传、备份、preflight、发布、smoke、DNS 和回滚都遵循根手册中的 dry-run、备份和
  精确确认字符串门禁。

历史 Cloudflare 原型说明 `docs/project/13-cloudflare-deployment-guide.md` 不属于
当前生产批准流程。
