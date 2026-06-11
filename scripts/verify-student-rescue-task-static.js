const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  "function isTeacherRescueTask",
  "function renderTeacherTaskRescueCard",
  "teacher-task-rescue-card",
  "teacher-task-rescue-route",
  "aria-label=\"个人补救任务引导\"",
  "这是老师为你单独安排的短任务",
  "个人短任务",
  "读定义",
  "看变化",
  "跑例子",
  "交复盘",
  "renderTeacherTaskRescueCard(task, progress)",
  "data-teacher-task-action=\"${escapeAttr(item.id)}\""
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing student rescue task markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`student-rescue-task-static-ok markers=${markers.length}`);
