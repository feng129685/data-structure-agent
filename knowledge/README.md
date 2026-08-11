# 私有课程知识库接入说明

本目录只保存知识库接入说明。完整教材 OCR、习题答案和其他受版权保护的课程材料应放在 `private/knowledge/`，该目录已被 `.gitignore` 排除，不应上传到公开 GitHub 仓库。

## 导入教材包

在 PowerShell 中执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\import-knowledge-pack.ps1 "D:\path\to\knowledge-pack.zip"
```

脚本默认导入到 `private/knowledge/`，只提取：

- `lessons/*.md`：按课时拆分的教材内容。
- `raw/answer_by_chapter.json`：按章整理的习题参考答案。
- 资料包自带的 `README.md`。

原始 361 页 OCR JSON 和 `source_normalized/` 源码暂不进入在线检索。当前抽查发现部分课时与源码文件存在错配，必须先由团队人工核对。

## 运行方式

后端启动时会自动读取 `KNOWLEDGE_DIR` 并建立内存检索索引。默认目录是：

```text
private/knowledge
```

提问时，后端会根据问题和当前章节召回相关课时片段，将最多 4 条结果放入模型上下文。教材目录不存在或加载失败时，聊天服务会自动退回原有章节上下文，不影响启动。

本地抽查检索结果时，可临时设置：

```env
KNOWLEDGE_DEBUG_API=true
```

然后访问：

```text
GET /api/knowledge/search?q=单链表插入为什么要先保存后继指针
```

正式公网环境建议保持 `KNOWLEDGE_DEBUG_API=false`，避免通过调试接口公开教材片段。

## 质量边界

- OCR 尚未完成人工逐页校对，模型提示词已要求忽略明显乱码并避免伪造页码。
- 检索结果应视为课程依据候选，而不是绝对正确答案。
- 正式教学使用前，应优先校对高频章节：线性表、栈与队列、树、图、查找和排序。
- 教材第 2 版与习题答案第 3 版可能存在题号或表述差异，展示答案时应保留版本说明。
