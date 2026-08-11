const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "本周复习任务：",
  "【本周数据结构复习任务】",
  "根据班级薄弱点、章节进度和复盘情况自动整理",
  "publishSteps",
  "Array.isArray(template.publishSteps)",
  "观察动画",
  "验证边界",
  "提交复盘"
];

const missingHtml = markers.filter((marker) => !html.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missingHtml.length || missingIndex.length) {
  if (missingHtml.length) {
    console.error("Missing teacher weekly task prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher weekly task index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-weekly-task-static-ok markers=${markers.length}`);
