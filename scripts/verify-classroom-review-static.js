const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  'id="classroomReviewStrip"',
  'id="classroomHarvestCard"',
  "classroomReviewStrip: document.getElementById(\"classroomReviewStrip\")",
  "classroomHarvestCard: document.getElementById(\"classroomHarvestCard\")",
  "function renderClassroomReviewStrip",
  "function buildClassroomHarvest",
  "function renderClassroomHarvestCard",
  "async function saveClassroomHarvestNote",
  "function buildClassroomCheckpointSummary",
  "function buildClassroomReviewPrompt",
  "function handleClassroomReviewAction",
  "data-classroom-review",
  "data-classroom-save-harvest",
  "保存课堂纪要",
  "课堂纪要",
  "classroom-harvest",
  "刚完成：${concept}",
  "请帮我复盘刚才课堂检查点里的回答",
  "safeKind === \"answer\"",
  "生成练习",
  "带回伴学追问",
  "去 C 验证",
  "data-classroom-review=\"compiler\"",
  "source: \"课堂到 C 实验\"",
  "action: \"classroom-compiler\"",
  "openCompilerFromOrchestrator(state.classroom.lastQuestion || currentScenario().chapter)",
  "[\"summary\", \"practice\", \"coach\", \"animate\", \"answer\", \"compiler\"]",
  "讨论结束后整理一下",
  "renderClassroomHarvestCard(state.classroom.board || [])",
  "renderClassroomReviewStrip();",
  "handleClassroomReviewAction(button.dataset.classroomReview)",
  "saveButton.addEventListener(\"click\", () => saveClassroomHarvestNote(saveButton))",
  "markLearningStep(safeKind === \"practice\" ? \"quiz\" : \"ask\", state.currentScenario)",
  "syncViewRoute(\"coach\")"
];

const failures = [];

for (const file of files) {
  const html = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !html.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing classroom-review markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`classroom-review-static-ok files=${files.length} markers=${markers.length}`);
