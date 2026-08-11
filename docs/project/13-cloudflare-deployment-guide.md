# Cloudflare 与香港云服务器部署指南

更新时间：2026-06-02

这份指南回答一个实际问题：如果你有一台香港云服务器，能不能把“数据结构学习陪练台”部署上去，再在 Cloudflare 里创建一个子域名访问？

结论：**可以**。但要先区分你要部署的到底是哪一类“智能体”。

## 1. 先判断部署对象

| 你要部署的东西 | 能不能放到自己的香港服务器 | 推荐方式 |
|---|---|---|
| `prototype.html` 本地演示原型 | 可以 | Nginx / Caddy 静态站点 |
| 自己开发的前端页面 | 可以 | 前端静态部署或 Node 服务 |
| 自己开发的智能体后端 | 可以 | Node / Python 服务 + 反向代理 |
| 自托管 Dify 等开源平台 | 可以，但服务器配置要求更高 | Docker Compose |
| Coze、腾讯元器、文心智能体等第三方平台本体 | 通常不可以 | 只能分享平台链接，或做一个外部入口页 |

如果只是先给老师看，最轻的路线是：**把 `prototype.html` 和项目说明部署到服务器，用子域名打开；真实智能体仍先在 Dify / Coze / 腾讯元器等平台里搭建。**

## 2. 推荐路线 A：DNS 解析到香港服务器

适合情况：你有服务器公网 IP，并且愿意开放 80 / 443 端口。

### 操作步骤

1. 在服务器上部署站点或服务。
   - 静态原型：把 `prototype.html` 放到 Nginx / Caddy 的站点目录。
   - 自建后端：让服务监听本地端口，例如 `127.0.0.1:3000`。

2. 在服务器上配置反向代理。
   - 访问 `agent.example.com` 时转发到你的静态目录或本地服务端口。

3. 在 Cloudflare DNS 中添加记录。

| 字段 | 示例 |
|---|---|
| Type | `A` |
| Name | `agent` |
| Content | 你的香港服务器公网 IP |
| Proxy status | Proxied，橙色云朵 |

4. 在 Cloudflare 的 SSL/TLS 中设置加密模式。
   - 推荐：`Full (strict)`。
   - 前提：你的服务器源站有有效证书，例如 Let’s Encrypt 证书或 Cloudflare Origin CA 证书。
   - 不建议长期使用 `Flexible`，因为它只加密浏览器到 Cloudflare 这一段，Cloudflare 到源站仍可能是 HTTP。

5. 在服务器安全组或防火墙放行。
   - 必要端口：`80`、`443`。
   - 如果服务只通过 Nginx / Caddy 反代，应用端口不必直接暴露到公网。

### 适合你的实践方式

```text
访问者
  ↓
https://agent.your-domain.com
  ↓
Cloudflare
  ↓
香港服务器 Nginx / Caddy
  ↓
prototype.html 或本地智能体服务
```

## 3. 推荐路线 B：Cloudflare Tunnel

适合情况：你不想暴露服务器公网 IP，或者不想开放 80 / 443 入站端口。

Cloudflare Tunnel 的思路是：在服务器上运行 `cloudflared`，它主动连到 Cloudflare；Cloudflare 再把 `agent.example.com` 的访问转发到你服务器上的本地服务。

### 操作步骤

1. 在香港服务器上启动你的服务。
   - 例如本地服务运行在 `http://localhost:3000`。

2. 在 Cloudflare Zero Trust 中创建 Tunnel。

3. 在 Tunnel 里添加 Public Hostname。

| 字段 | 示例 |
|---|---|
| Public hostname | `agent.example.com` |
| Service | `http://localhost:3000` |

4. 在服务器上运行 `cloudflared`。
   - 可以用 systemd 或 Docker 让它常驻运行。

5. 访问 `https://agent.example.com` 测试。

### 适合你的实践方式

```text
访问者
  ↓
https://agent.your-domain.com
  ↓
Cloudflare
  ↓
Cloudflare Tunnel
  ↓
香港服务器 localhost:3000
```

这条路线的好处是服务器不需要直接暴露 Web 端口，后续也方便把后台管理页、原型页、API 服务拆成不同子域名。

## 4. 你这个项目的推荐部署组合

第一阶段建议这样做：

| 目标 | 推荐方案 |
|---|---|
| 给老师看原型 | 部署 `prototype.html` 到 `agent.your-domain.com` |
| 给老师体验真实智能体 | 先用 Dify / Coze / 腾讯元器生成分享链接 |
| 整合展示入口 | 在服务器上做一个轻量入口页，放原型、真实智能体链接、测试报告 |
| 后续升级 | 再考虑自建前端 + 后端 API + 模型服务 |

也就是说，首版不用一上来就把所有智能体逻辑都部署到服务器。更稳的做法是：

1. 服务器负责“展示入口”和“原型页面”。
2. 智能体平台负责“真实问答能力”。
3. Cloudflare 子域名负责“统一访问入口”。

## 5. 如果要自托管智能体

如果你想让智能体完全运行在自己的香港服务器上，需要额外准备：

- 模型 API Key，或本地模型推理环境。
- 后端服务，用来处理聊天、知识库检索、提示词编排。
- 向量数据库或知识库方案。
- 日志和测试记录。
- HTTPS、反向代理和基础安全配置。

这条路线更自由，但工作量明显更大。除非老师明确要求“必须自建”，否则第一阶段更建议先用平台搭建，再用服务器做展示入口。

## 6. 官方文档参考

- Cloudflare DNS 代理状态：`https://developers.cloudflare.com/dns/proxy-status/`
- Cloudflare DNS 记录代理说明：`https://developers.cloudflare.com/dns/manage-dns-records/reference/proxied-dns-records/`
- Cloudflare SSL/TLS 加密模式：`https://developers.cloudflare.com/learning-paths/get-started/security/ssl-tls`
- Cloudflare SSL/TLS 快速开始：`https://developers.cloudflare.com/ssl/get-started/`
- Cloudflare Tunnel Routing：`https://developers.cloudflare.com/tunnel/routing/`
- Cloudflare Tunnel Setup：`https://developers.cloudflare.com/tunnel/setup/`

## 7. 最小上线检查表

| 检查项 | 状态 |
|---|---|
| 子域名已确定，例如 `agent.example.com` | 已完成：`agent.example.com` |
| 香港服务器公网 IP 或 Tunnel 已准备 | 已完成：`YOUR_SERVER_IP` |
| 原型或前端服务能在服务器本机访问 | 已完成：Caddy 静态站点 |
| Cloudflare DNS 或 Tunnel Public Hostname 已配置 | 已完成：A 记录指向服务器并开启代理 |
| HTTPS 可正常访问 | 已完成：`https://agent.example.com` 返回 200 |
| 老师能从外网打开页面 | 待人工确认 |
| 页面中有真实智能体平台链接或嵌入入口 | 待确认 |
