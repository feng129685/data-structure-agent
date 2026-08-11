const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");

const markers = [
  "function getTeacherAssignmentSourceLabel",
  "function buildTeacherTaskSourceChips",
  "teacher-task-source-row",
  "aria-label=\"任务来源\"",
  "createdAt: item.createdAt || item.created_at || null",
  "createdAt: remoteAssignment.createdAt",
  "updatedAt: remoteAssignment.updatedAt",
  "全班可见",
  "分层精准任务",
  "teacher-published-meta",
  "aria-label=\"发布状态\"",
  "分层诊断 · 已闭环",
  "分层诊断 · 需复盘",
  "分层诊断 · 需干预",
  "teacher-layered-publish-note",
  "发布后只进入这组学生的首页任务队列",
  "buildTeacherTaskSourceChips(task, progress)"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing teacher assignment source markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`teacher-assignment-source-static-ok markers=${markers.length}`);
