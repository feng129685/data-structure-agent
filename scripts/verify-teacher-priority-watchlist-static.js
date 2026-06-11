const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "index.html"), "utf8");

const markers = [
  "function buildTeacherPriorityWatchlist",
  "function renderTeacherPriorityWatchlist",
  "renderTeacherPriorityWatchlist(overview)",
  "teacher-priority-list",
  "teacher-priority-card",
  "teacher-priority-badge",
  "data-teacher-priority-advice",
  "teacherPriorityAdvice",
  "优先跟进名单",
  "复制跟进建议",
  "闭环证据",
  "读一句定义、看一步动画、跑一个 C 例子、交一句复盘",
  "teacher-priority-list,"
];

function missingMarkers(text) {
  return markers.filter((marker) => !text.includes(marker));
}

const missingPrototype = missingMarkers(prototype);
const missingIndex = missingMarkers(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing teacher priority watchlist prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher priority watchlist index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-priority-watchlist-static-ok markers=${markers.length}`);
