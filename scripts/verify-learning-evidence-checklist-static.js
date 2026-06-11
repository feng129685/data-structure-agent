const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const files = ["prototype.html", "index.html"];

const htmlMarkers = [
  "learning-evidence-card",
  "learning-evidence-item",
  "learning-evidence-action",
  "data-learning-evidence-action",
  "闭环证据清单",
  "function matchLearningEvidenceNote",
  "function buildLearningEvidenceChecklist",
  "function renderLearningEvidenceChecklist",
  "function renderLearningEvidenceMini",
  "function buildLearningEvidenceChecklistText",
  "function copyLearningEvidenceChecklist",
  "function handleLearningEvidenceAction",
  "renderLearningEvidenceChecklist(studyReport)",
  "renderLearningEvidenceMini(studyReport, scenarioId)",
  "data-learning-evidence-mini",
  "learning-evidence-mini",
  "learning-path-meta",
  ".learning-evidence-mini button:focus-visible",
  ".teacher-task-evidence-chip:focus-visible",
  ".teacher-task-next-action:focus-visible",
  "width: 100%;",
  "justify-content: space-between;",
  "const scenario = scenarioData[scenarioId] ? scenarioId : state.currentScenario",
  "nextScenario: scenario",
  "闭环证据进度",
  "证据 ${escapeHtml(checklist.status)}",
  "handleLearningEvidenceAction(button.dataset.learningEvidenceAction, button.dataset.learningEvidenceScenario)",
  "learningEvidence: {",
  "action === \"materials\"",
  "action === \"animation\"",
  "action === \"compiler\"",
  "action === \"classroom\"",
  "openCompilerFromOrchestrator(chapter)",
  "openMaterialsChapter(scenario)",
  "state.activeView = \"materials\";",
  "syncViewRoute(\"materials\");",
  "openClassroomDiscussion(`请围绕「${chapter}」讨论一个容易混淆的状态变化或边界情况。`)",
  "闭环证据清单已复制",
  "闭环证据 · ${Number(totals.evidenceNoteCount || 0)}",
  "位已形成证据"
];

const serverMarkers = [
  "function normalizeLearningEvidence",
  "const learningEvidence = normalizeLearningEvidence(value.learningEvidence)",
  "learningEvidence,",
  "闭环证据清单：",
  "const evidenceStudentIds = new Set()",
  "let evidenceNoteCount = 0",
  "evidenceNoteCount,",
  "evidenceStudentCount: evidenceStudentIds.size"
];

const failures = [];

for (const file of files) {
  const html = fs.readFileSync(path.join(root, file), "utf8");
  const missing = htmlMarkers.filter((marker) => !html.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

const server = fs.readFileSync(path.join(root, "server.js"), "utf8");
const serverMissing = serverMarkers.filter((marker) => !server.includes(marker));
if (serverMissing.length) failures.push({ file: "server.js", missing: serverMissing });

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing learning-evidence-checklist markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`learning-evidence-checklist-static-ok files=${files.length + 1} markers=${htmlMarkers.length + serverMarkers.length}`);
