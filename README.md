# Data Structure Agent

一个面向数据结构课程的 AI 学习智能体工作台，帮助学生完成"看懂知识点、理解过程、练习题目、定位错误、生成复习建议"的学习闭环。

## 功能特性

- **AI 陪练对话** - 基于课程知识库的智能问答，支持多轮对话和学习上下文记忆
- **动画可视化** - 交互式数据结构动画演示（栈、队列、链表、二叉树、图等）
- **在线编译器** - 内置 C/C++ 代码编辑与运行环境
- **学习资料库** - 结构化的课程讲义、习题和参考材料（PDF）
- **课堂讨论** - 教师-学生互动讨论区，支持任务分配和学习追踪
- **教师工作台** - 作业发布、学生进度追踪、学习证据收集和诊断报告
- **学习雷达** - 个人学习进度可视化和薄弱点分析
- **移动端适配** - 完整的响应式设计，支持手机端使用

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Node.js (>=18) |
| 数据库 | SQLite (better-sqlite3) |
| 认证 | JWT (jsonwebtoken) |
| AI 模型 | MIMO API |
| 文档解析 | pdf-parse, mammoth, officeparser |
| 邮件通知 | nodemailer |
| 前端 | 原生 HTML/CSS/JavaScript 单页应用 |

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
cp .env.example .env
```

编辑 `.env` 文件：

```env
HOST=127.0.0.1
PORT=8791

# AI 模型配置
MIMO_API_KEY=your_api_key_here
MIMO_BASE_URL=https://token-plan-cn.xiaomimimo.com/
MIMO_MODEL=mimo-v2.5-pro

# 邮件通知（可选）
SMTP_HOST=smtp.example.com
SMTP_PORT=465
SMTP_USER=your_smtp_user
SMTP_PASS=your_smtp_password
SMTP_FROM=noreply@example.com
```

### 启动

```bash
# 生产模式
npm start

# 开发模式（自动重启）
npm run dev
```

服务启动后访问 `http://localhost:8791`

### 运行测试

```bash
npm test
```

## 项目结构

```
data-structure-agent/
├── server.js              # 后端服务主入口
├── index.html             # 前端主页面
├── prototype.html         # 前端原型页面
├── package.json           # 项目配置
├── scripts/               # 测试与验证脚本
│   ├── verify-core-regression.js
│   ├── verify-*-static.js # 各模块功能验证
│   └── deep_check.py      # 深度检查工具
├── pdfs/                  # 课程学习资料（PDF 讲义）
├── docs/                  # 项目文档
│   └── superpowers/       # 设计方案与规格文档
├── 00-current-status.md   # 项目进度总览
├── 01-project-proposal.md # 项目方案
├── 03-agent-prompts.md    # 智能体提示词
└── ...                    # 其他项目文档
```

## 文档索引

| 文件 | 说明 |
|------|------|
| `00-current-status.md` | 当前进度总览与下一步路线 |
| `01-project-proposal.md` | 项目方案与价值说明 |
| `02-platform-build-guide.md` | 平台搭建操作步骤 |
| `03-agent-prompts.md` | 主智能体和子智能体提示词 |
| `04-knowledge-base-seed.md` | 知识库首批内容模板 |
| `05-test-cases.md` | 测试用例 |
| `06-demo-script.md` | 展示脚本 |
| `09-iteration-report.md` | 迭代优化记录 |
| `13-cloudflare-deployment-guide.md` | Cloudflare 部署说明 |

## 支持的数据结构

首版覆盖以下核心模块：

- 栈 (Stack)
- 队列 (Queue)
- 链表 (Linked List)
- 二叉树 (Binary Tree)
- 图遍历 (Graph Traversal)

## License

MIT
