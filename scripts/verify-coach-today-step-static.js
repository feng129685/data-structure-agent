const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  "coach-today-step",
  "coachTodayStep: document.getElementById(\"coachTodayStep\")",
  "function buildCoachTodayStep",
  "function renderCoachTodayStep",
  "pickLearningEvidenceRecommendation(\"coach\", scenario, \"\")",
  "data-coach-today-action",
  "data-coach-today-scenario",
  "handleLearningEvidenceAction(",
  "openClassroomDiscussion(`请围绕「${chapter}」讨论一个容易混淆的状态变化或边界情况。`)",
  "showToast(`已打开「${chapter}」资料`, \"success\")",
  "showToast(`已带入「${chapter}」复盘提示`, \"success\")",
  "renderCoachTodayStep();",
  "今日一步学习建议"
];

const failures = [];

for (const file of files) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing coach today step markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`coach-today-step-static-ok files=${files.length} markers=${markers.length}`);
