const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const files = ["prototype.html", "index.html"];

const markers = [
  "classroom-harvest-card",
  "课堂收获卡",
  "function buildClassroomHarvest",
  "function renderClassroomHarvestCard",
  "async function saveClassroomHarvestNote",
  "data-classroom-save-harvest",
  "saveButton.addEventListener(\"click\", () => saveClassroomHarvestNote(saveButton))",
  "async function saveClassroomHarvestNote(button = null)",
  "button.disabled = true",
  "button.textContent = \"已保存到学习档案\"",
  "button.dataset.saved = \"true\"",
  "scheduleLearningSnapshotSync(400)",
  "renderHomeLearningLoop();",
  "保存课堂纪要",
  "已保存到学习档案",
  "source: \"课堂纪要\"",
  "action: \"classroom-harvest\"",
  "tag: \"闭环证据\"",
  "evidence: `课堂讨论：",
  "markLearningStep(\"ask\", state.currentScenario)",
  "if (state.activeView === \"profile\") renderProfile();",
  "saveActiveThread(\"classroom\", { sync: Boolean(authToken) })",
  "课堂纪要已保存",
  "保存后会进入学习档案",
  "本轮课堂讨论"
];

const failures = [];

for (const file of files) {
  const html = fs.readFileSync(path.join(root, file), "utf8");
  const missing = markers.filter((marker) => !html.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing classroom-harvest markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`classroom-harvest-static-ok files=${files.length} markers=${markers.length}`);
