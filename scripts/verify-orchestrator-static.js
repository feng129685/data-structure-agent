const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const indexPath = path.join(__dirname, "..", "index.html");
const html = fs.readFileSync(htmlPath, "utf8");
const index = fs.readFileSync(indexPath, "utf8");

const markers = [
  "function buildLearningOrchestratorPlan",
  "function renderLearningOrchestratorCard",
  "function attachLearningOrchestratorListeners",
  "function openCompilerFromOrchestrator",
  "function formatAssistantForLearning",
  "data-learn-action",
  "learning-orchestrator-card",
  "learning-orchestrator-actions",
  ".slice(0, 3)",
  "message.learningPlan && idx === messages.length - 1",
  "先看状态变化，再回到题目会更清楚。"
];

const missing = markers.filter((marker) => !html.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missing.length || missingIndex.length) {
  console.error("Missing orchestrator markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  if (missingIndex.length) {
    console.error("Missing orchestrator index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`orchestrator-static-ok markers=${markers.length}`);
