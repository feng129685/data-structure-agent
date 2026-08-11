const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "function buildTodayClosurePlan",
  "function renderTodayClosureCard",
  "function sendTodayClosureToCoach",
  "renderTodayClosureCard(scenarioId)",
  "today-closure-card",
  "today-closure-steps",
  "data-today-closure-action",
  "data-today-closure-teacher",
  "sendTodayClosureToCoach(button.dataset.todayClosureScenario)",
  "今日学习闭环进度",
  "今天还差一步",
  "把今日闭环带回伴学复盘",
  "我今天已经完成",
  "teacherProgress.complete"
];

function missingMarkers(text) {
  return markers.filter((marker) => !text.includes(marker));
}

const missingPrototype = missingMarkers(prototype);
const missingIndex = missingMarkers(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing today closure card prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing today closure card index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`today-closure-card-static-ok markers=${markers.length}`);
