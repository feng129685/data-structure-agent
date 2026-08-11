const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "function buildTeacherClassBrief",
  "function renderTeacherClassBrief",
  "teacher-brief-card",
  "teacher-brief-signals",
  "本周教学简报",
  "建议发布任务",
  "复制本周安排",
  "data-teacher-class-brief",
  "copyTeacherFollowup(button.dataset.teacherClassBrief)",
  "renderTeacherClassBrief(overview)",
  "共同薄弱点",
  "低进度章节",
  "需要关注"
];

const missingHtml = markers.filter((marker) => !html.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missingHtml.length || missingIndex.length) {
  if (missingHtml.length) {
    console.error("Missing teacher class brief prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher class brief index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-class-brief-static-ok markers=${markers.length}`);
