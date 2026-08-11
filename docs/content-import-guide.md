# 课程资料与课堂脚本导入说明

本说明供资料/PPT 负责人、课堂负责人和后端负责人协作使用。目标是让“栈与队列”先形成一条可审核、可复现、可追踪的学习闭环，而不是把未经核验的文件或模型文本直接放到线上。

## 1. 私有目录边界

教材 OCR、教师 PPT、受限 PDF、原始录屏和授权证明都放在团队私有目录或私有仓库，不能提交到公开 GitHub：

```text
private/course-content/
  chapters/
    03-stack-queue/
      slides/
      code/
      exercises/
      classroom/
  sources/
  review-records/
```

`resources.file_path` 只能填写相对于 `RESOURCE_DIR` 的正斜杠路径，例如：

```text
chapters/03-stack-queue/slides/stack-introduction.pdf
```

禁止绝对路径、`..`、盘符路径和软链接跳出 `RESOURCE_DIR`。后端会再次校验这些规则，并且对前端只返回 `/api/v1/resources/{id}/content`，不会泄露真实目录结构。

## 2. 资料入库前检查

每份资料至少补齐以下信息：

| 字段 | 要求 |
|---|---|
| `id` | 永不复用的稳定编号，例如 `03-stack-slides-v1`。 |
| `chapterId` | 已发布章节编号，例如 `03-stack-queue`。 |
| `type` | `TEXTBOOK`、`PPT`、`CODE`、`PSEUDOCODE`、`EXERCISE` 或团队统一的分类。 |
| `title` / `description` | 面向学生的准确名称和用途。 |
| `sourceName` | 教材、教师课件或原创资料的可追溯来源。 |
| `versionLabel` | 例如 `2026.07-v1`。 |
| `reviewStatus` | 未审为 `DRAFT`，完成审核后才为 `PUBLISHED`。 |
| `licenseScope` | `PUBLIC`、`TEAM_ONLY` 或 `CLASSROOM_ONLY`。 |
| `reviewer` | 在 `content_reviews` 中记录审核账号和意见。 |

建议流程：资料负责人整理文件和来源，课堂负责人核对能否映射到讲解/问题/动画，后端负责人执行导入，教师或指定审核人确认后将资料状态改为 `PUBLISHED`。

## 3. 受控导入方式

首版不提供面向公网的“上传即发布”接口。资料导入应通过受控的数据库迁移、内部管理工具或具备最小权限的运维数据库连接完成。

资源元数据可使用下面的参数化 SQL 模板。不要把真实文件路径、密钥或受限正文提交到公开仓库。

```sql
INSERT INTO resources (
  id, chapter_id, resource_type, title, description, file_path,
  source_name, version_label, review_status, license_scope
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  description = VALUES(description),
  file_path = VALUES(file_path),
  source_name = VALUES(source_name),
  version_label = VALUES(version_label),
  updated_at = CURRENT_TIMESTAMP;
```

审核通过后，由审核人写入 `content_reviews`，再将该资源更新为 `PUBLISHED`。线上资料查询要求资源及其所属章节都为 `PUBLISHED`；即使资料本身已经发布，草稿章节下的列表、详情和文件正文仍不可访问。

发布状态不等于所有人可见。后端会在资料列表、元数据详情和文件正文三个入口执行同一套许可规则，范围外的资料统一返回 `404`：

| 访问者 | 可见的 `licenseScope` |
|---|---|
| 未登录访客 | `PUBLIC` |
| 已登录学生 | `PUBLIC`、`CLASSROOM_ONLY` |
| `TEACHER` / `ADMIN` | `PUBLIC`、`CLASSROOM_ONLY`、`TEAM_ONLY` |

因此，准备公开演示的资料必须显式标记为 `PUBLIC`；教师底稿、授权证明和团队内部素材应保持 `TEAM_ONLY`。不要依靠前端隐藏按钮实现权限控制。

生产环境默认设置 `KNOWLEDGE_AUTO_PUBLISH_LOCAL=false`。此时挂载到 `KNOWLEDGE_DIR` 的 OCR 课时不会因为文件存在就自动进入问答检索，后端只加载数据库里已经标记为 `PUBLISHED` 的知识片段。仅在本地开发或使用明确完成审核的课时包时，才可显式开启自动发布。

知识片段也必须有明确的 `license_scope`。当 `knowledge_chunks.resource_id` 关联资料时，检索权限以对应 `resources.license_scope` 为准，并且资料及所属章节都必须已发布；未关联资料时，以知识片段自身的 `license_scope` 为准。手工导入未关联片段时应显式填写授权范围，未知值会按不可见处理。本地教材导入默认使用 `CLASSROOM_ONLY`，不能作为游客公开语料。

## 4. 课堂脚本

课堂脚本正文存入 `classroom_scripts.script_json`，元数据存入同一行的 `id`、`chapter_id`、`title`、`version_label` 和 `review_status`。格式以以下文件为准：

- [`contracts/classroom-script.schema.json`](../contracts/classroom-script.schema.json)
- [`contracts/examples/classroom/stack-lesson.json`](../contracts/examples/classroom/stack-lesson.json)
- [`contracts/examples/classroom/queue-lesson.json`](../contracts/examples/classroom/queue-lesson.json)

脚本中的 `expected`、`misconceptions` 和 `misconceptionFeedback` 仅用于后端评价学生回答。课堂会话返回给学生的 `QUESTION` 与 `WAITING` 阶段会自动移除这些字段，前端不能尝试在浏览器中保管或计算标准答案。

以下字段用于把课堂内容接到其它模块：

| 字段 | 对接对象 |
|---|---|
| `contentRef` | 审核后的讲义或 PPT 页面。 |
| `animationRef` | 结构化动画样例或可生成动画的稳定编号。 |
| `codeRef` | 对应章节的代码实验示例。 |
| `prompt` / `expected` | 课堂提问和后端答案评价。 |

先验证脚本 JSON，再导入：

```powershell
cd backend/spring
.\mvnw.cmd "-Dtest=ContractExampleCompatibilityTest,ClassroomScriptParserTest" test
```

新脚本在通过教师审核前保持 `DRAFT`。发布后若修改核心定义、伪代码、复杂度或标准答案，应提升 `version_label` 并保留审核记录。

## 5. 学习证据与复现规则

以下记录由具体业务流程在后端生成，前端或通用学习事件接口不能自行声明：

| 证据类型 | 可信来源 | 保存内容 |
|---|---|---|
| `CLASSROOM_ANSWER` | 已登录用户提交课堂回答 | 会话使用的脚本快照、评价状态、命中的误区和反馈。 |
| `ANIMATION_OBSERVATION` | 已登录用户对本人动画提交观察 | 每次观察都追加保存；动画主记录只保留最新值用于兼容。 |
| `CODE_REVIEW` | 使用本人代码运行记录的 `runId` 完成智能分析 | 后端重新读取已保存的源码与运行结果，不信任客户端回传的结果字段。 |

课堂会话创建后会冻结当时的 `script_json` 和章节归属。即使资料负责人随后修改、移动或发布脚本新版本，已经开始的课堂仍按原快照继续，确保教师复查时可以还原学生当时看到的内容，并把学习进度计入原章节。需要使用新脚本时，应创建新会话，不要修改历史会话。

通用 `POST /api/v1/learning/events` 只用于资料查看、资料下载、复习完成和薄弱点记录。内容与前端负责人不应直接提交上述三种可信证据，也不应在浏览器中伪造代码运行结果、课堂评价或动画归属。

## 6. 栈与队列验收清单

第一批内容至少满足：

1. 栈和队列各有一份经过审核的讲义/PPT、一个课堂脚本、一个动画样例和一个代码示例。
2. 课堂问题的正确答案和两个常见误区能触发不同的后端评价结果。
3. 资料页能通过章节列表进入预览或下载，且 URL 不包含服务器真实路径。
4. 动画观察、课堂回答和资料查看能写入同一位登录用户的学习记录。
5. 私有教材、教师 PPT、授权证明和真实服务密钥均不在公开仓库中。
