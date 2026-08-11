const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "function buildTeacherReviewBrief",
  "【数据结构课后复盘】",
  "复制课堂复盘稿",
  "data-teacher-review-brief",
  "teacherReviewBrief",
  "teacher-diagnosis-action primary",
  "最近反馈集中在"
];

const missingHtml = markers.filter((marker) => !html.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missingHtml.length || missingIndex.length) {
  if (missingHtml.length) {
    console.error("Missing teacher review brief prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher review brief index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-review-brief-static-ok markers=${markers.length}`);
