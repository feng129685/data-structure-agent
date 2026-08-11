const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const htmlMarkers = [
  "function pickLearningEvidenceRecommendation",
  "function buildLearningOrchestratorPlan",
  "version: 3",
  "evidenceStatus: recommendation.checklist.status",
  "actions: [recommendation.action]",
  "learningPlan.version !== 3",
  "handleLearningEvidenceAction(\"coach\", scenario)",
  "还缺：${chosenRow.title}",
  "当前闭环进度 ${doneCount}/${total}",
  "证据已齐，适合做总结",
  "data-learn-action",
  "learning-orchestrator-card"
];

const serverMarkers = [
  "最后只给一个自然的下一步建议",
  "闭环证据清单",
  "不要在结尾堆多个功能入口"
];

const failures = [];

for (const file of files) {
  const html = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = htmlMarkers.filter((marker) => !html.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));
if (missingServer.length) failures.push({ file: "backend/node/server.js", missing: missingServer });

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing evidence-aware orchestrator markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`evidence-aware-orchestrator-static-ok files=${files.length + 1} markers=${htmlMarkers.length + serverMarkers.length}`);
