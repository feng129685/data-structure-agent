# Structify Production Release Runbook (compatibility entry)

完整、唯一的中文执行流程在项目根目录的
[`PRODUCTION_DEPLOYMENT_GUIDE.md`](../../PRODUCTION_DEPLOYMENT_GUIDE.md)。本文件保留
旧路径，供历史链接和工具引用；密码、上传、备份、preflight、`release.sh`、验收和
回滚步骤以根目录手册为准。

## Current release boundary

- SSH host: `49.232.245.99`
- SSH user: `ubuntu`
- SSH port: `22`
- Current release: `v1.0.18-structify`
- Public hosts: `https://structify.cn` and `https://admin.structify.cn`
- Current production Caddy owner: dedicated-host Compose container
- Normal rollout entry point: `deployment/scripts/release.sh`

## One-time credential rule

密码不是 `-Artifact`、`-Release` 或 `-HostName` 参数。已知密码时，执行：

~~~powershell
.\deployment\scripts\upload-release.ps1 -HostName 49.232.245.99 -User ubuntu -Port 22 -SaveCredential
~~~

在安全提示中按原样输入密码（包括末尾句点，如有）。凭据由 Windows DPAPI 保存到
`%LOCALAPPDATA%\Structify\credentials\production-ssh.xml`，后续上传自动读取。没有
密码且没有 SSH 私钥时，先从云厂商控制台、VNC/串口控制台或运维管理员处重置 `ubuntu`
密码或安装公钥；脚本不能生成或找回未知密码。

## Stop conditions

不得用 DPanel、工作树、`git reset`、`git clean`、手工数据库修改、DNS 猜测或临时
Caddy 模式切换绕过校验。SHA-256、备份、execute-time preflight、Flyway、回环健康、
公网 smoke 或 Caddy 拓扑任一失败，都必须停止并按根手册的恢复/回滚流程处理。
