const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "teacher-action-strip",
  "teacher-action-card",
  "teacher-action-copy",
  "function buildTeacherActionSummary",
  "function renderTeacherActionSummary",
  "data-teacher-action-summary",
  "教师下一步行动摘要",
  "先讲什么",
  "布置什么",
  "关注谁",
  "renderTeacherActionSummary(overview)",
  "copyTeacherFollowup(button.dataset.teacherActionSummary)",
  "复盘覆盖 ${reviewCoverage}",
  "完整学习闭环",
  "15 分钟复习路径"
];

function missingIn(html) {
  return markers.filter((marker) => !html.includes(marker));
}

const missingPrototype = missingIn(prototype);
const missingIndex = missingIn(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing teacher-action-summary prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher-action-summary index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-action-summary-static-ok markers=${markers.length}`);
