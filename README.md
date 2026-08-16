# Data Structure Agent

一个面向数据结构课程的 AI 学习智能体工作台，帮助学生完成"看懂知识点、理解过程、练习题目、定位错误、生成复习建议"的学习闭环。

## 功能特性

- **AI 陪练对话** - 基于课程知识库的智能问答，支持多轮对话和学习上下文记忆
- **动画可视化** - 交互式数据结构动画演示（栈、队列、链表、二叉树、图等）
- **在线编译器** - 内置 C/C++ 代码编辑与运行环境
- **学习资料库** - 结构化的课程讲义、习题和参考材料（PDF）
- **教材检索增强** - 从私有课时 Markdown 和章节习题答案中召回相关片段，连同来源交给模型回答
- **课堂讨论** - 教师-学生互动讨论区，支持任务分配和学习追踪
- **教师工作台** - 作业发布、学生进度追踪、学习证据收集和诊断报告
- **学习雷达** - 个人学习进度可视化和薄弱点分析
- **移动端适配** - 完整的响应式设计，支持手机端使用

本地完整课件只放在被 Git 忽略的 `private/` 目录：原始 PPT 位于
`private/source-ppt/`，审核后的页面清单和渲染图位于
`private/presentation-materials/`，教材和 PDF 分别位于
`private/knowledge/` 与 `private/pdfs/`。生产环境使用服务器只读挂载，发布树不包含这些文件。

## 技术栈

| 层级 | 技术 |
|------|------|
| Node 兼容服务 | Node.js (>=18), `backend/node` |
| 数据库 | SQLite (better-sqlite3) |
| 认证 | JWT (jsonwebtoken) |
| AI 模型 | OpenAI-compatible API（当前 DeepSeek） |
| 文档解析 | pdf-parse, mammoth, officeparser |
| 邮件通知 | nodemailer |
| 前端 | 原生 HTML/CSS/JavaScript 单页应用 |

## Spring 后端迁移

仓库现已包含独立的 Java 21 / Spring Boot 后端 [`backend/spring`](backend/spring)。它使用 MySQL、Flyway、Spring Security 和 `/api/v1/*` 接口，覆盖认证、章节资料、教材 RAG、普通与流式问答、脚本课堂、结构化动画、C/Python 沙箱编译、代码分析和学习进度。

当前采用并行迁移：

- `backend/node` 的 Node.js 兼容服务继续监听 `8791` 并提供旧 `/api/*`。
- Spring 后端监听 `8792` 并提供新 `/api/v1/*`。
- 前端完成新接口联调前，不直接替换线上 Node.js 服务。
- Spring 本地开发默认使用 H2，生产配置使用 MySQL。

```powershell
cd backend/spring
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

后端环境变量、管理员初始化、知识库、编译器安全和部署说明见 [`backend/spring/README.md`](backend/spring/README.md)，冻结的接口契约见 [`contracts/openapi-v1.yaml`](contracts/openapi-v1.yaml)。

生产学习站为 `https://structify.cn`，管理端统一使用 `https://admin.structify.cn`。完整的 Node 8791 / Spring 8792 / MySQL / Caddy 拓扑、私有教材和 PPT 路径、备份、迁移、健康检查、DNS 切换与回滚手册见项目根目录的 [`PRODUCTION_DEPLOYMENT_GUIDE.md`](PRODUCTION_DEPLOYMENT_GUIDE.md)；`docs/production-deployment.md` 仅是兼容入口。接口差异和数据模型差异分别见 [`docs/api-node-spring-differences.md`](docs/api-node-spring-differences.md) 与 [`docs/data-model-node-spring-differences.md`](docs/data-model-node-spring-differences.md)。Git 来源已恢复并核验：远程 `origin` 为 `https://github.com/feng129685/data-structure-agent.git`，截至本次核验 `origin/main` 为 `82b073790d28cffc47fbcbe500d111078d2660c3`（可用 `git ls-remote https://github.com/feng129685/data-structure-agent.git refs/heads/main` 复核）。当前工作区以该 revision 为基线但融合修改尚未提交，不能据此宣称生产线上版本等同；发布前必须创建并记录不可变 release commit/tag 和镜像摘要。

## 快速开始

### 环境要求

- Node.js >= 18.0.0
- npm

### 安装

```bash
git clone https://github.com/feng129685/data-structure-agent.git
cd data-structure-agent
npm install
```

### 配置

复制环境变量模板并填写：

```bash
cp .env.example backend/node/.env
```

`.env.example` 是无凭据模板。生产部署不要在仓库内编辑它；请使用 [`deployment/.env.spring.example`](deployment/.env.spring.example) 复制到 `/etc/structify/structify.env`，通过 secret manager 填入模型、SMTP、MySQL 和 JWT 值，并设置 `CORS_ALLOWED_ORIGINS=https://structify.cn,https://admin.structify.cn`、安全 Cookie、关闭调试/验证码捕获和静态管理员提升。

### 启动

```bash
# 生产模式
npm start

# 开发模式（自动重启）
npm run dev
```

服务启动后访问 `http://localhost:8791`

### 导入私有教材知识库

教材 OCR 不随公开仓库分发。拿到团队内部的知识包后，在 PowerShell 中执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\import-knowledge-pack.ps1 "D:\path\to\knowledge-pack.zip"
```

导入后重启服务。访问 `/healthz` 可查看 `knowledge.ready`、课时数和检索片段数。详细说明见 `knowledge/README.md`。

为了避免公开教材内容，`private/knowledge/` 已加入 `.gitignore`；公网环境也应保持 `KNOWLEDGE_DEBUG_API=false`。

### 运行测试

```bash
npm test
```

主回归包含教材检索、知识注入、安全响应头、文件上传权限、验证码限流以及模型流式错误/超时验证。

## 项目结构

```
data-structure-agent/
├── backend/               # 独立后端目录
│   ├── node/              # Node 兼容服务（/api/*）
│   └── spring/            # Spring 服务（/api/v1/*）
├── frontend/              # 正式静态前端
│   ├── index.html         # 正式主页面
│   ├── prototype.html     # 兼容原型入口
│   └── README.md          # 前端边界和迁移说明
├── package.json           # 项目配置
├── scripts/               # 测试与验证脚本
│   ├── verify-core-regression.js
│   ├── verify-*-static.js # 各模块功能验证
│   └── deep_check.py      # 深度检查工具
├── tools/                 # 本地材料工具与隔离的旧原型
├── fixtures/http/         # HTTP 请求/响应样例
├── knowledge/             # 公开知识库说明与导入边界
├── docs/                  # 项目文档
│   ├── project/           # 历史项目文档
│   └── superpowers/       # 设计方案与规格文档
└── private/               # 本地私有课件、状态和输出（永不发布）
```

## 文档索引

| 文件 | 说明 |
|------|------|
| `docs/project/00-current-status.md` | 当前进度总览与下一步路线 |
| `docs/project/01-project-proposal.md` | 项目方案与价值说明 |
| `docs/project/02-platform-build-guide.md` | 平台搭建操作步骤 |
| `docs/project/03-agent-prompts.md` | 主智能体和子智能体提示词 |
| `docs/project/04-knowledge-base-seed.md` | 知识库首批内容模板 |
| `docs/project/05-test-cases.md` | 测试用例 |
| `docs/project/06-demo-script.md` | 展示脚本 |
| `docs/project/09-iteration-report.md` | 迭代优化记录 |
| `docs/project/13-cloudflare-deployment-guide.md` | Cloudflare 部署说明 |
| `PRODUCTION_DEPLOYMENT_GUIDE.md` | `structify.cn` 生产发布唯一操作手册 |
| `docs/production-deployment.md` | 兼容入口，跳转到根目录生产手册 |
| `docs/api-node-spring-differences.md` | Node `/api/*` 与 Spring `/api/v1/*` 接口差异表 |
| `docs/data-model-node-spring-differences.md` | SQLite 到 MySQL 数据模型和导入边界 |

## 支持的数据结构

首版覆盖以下核心模块：

- 栈 (Stack)
- 队列 (Queue)
- 链表 (Linked List)
- 二叉树 (Binary Tree)
- 图遍历 (Graph Traversal)

## License

MIT
