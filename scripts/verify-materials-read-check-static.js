const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "materials-check-card",
  "const materialReadChecks",
  "function buildMaterialReadCheck",
  "function renderMaterialReadCheck",
  "function handleMaterialReadCheckAnswer",
  "function buildMaterialReadCheckReviewPrompt",
  "function handleMaterialReadCheckReview",
  "data-materials-check",
  "data-materials-check-answer",
  "data-materials-check-review",
  "资料读完检查",
  "source: \"资料读完检查\"",
  "action: \"materials-read-check\"",
  "action: \"materials-check-review\"",
  "recordWeakPoint({",
  "markLearningStep(\"quiz\", scenarioId)",
  "trackAchievement(\"quizAnswered\")",
  "${renderMaterialReadCheck(activeId)}",
  "handleMaterialReadCheckAnswer(button)",
  "handleMaterialReadCheckReview(reviewButton)"
];

function findMissing(html) {
  return markers.filter((marker) => !html.includes(marker));
}

const missingPrototype = findMissing(prototype);
const missingIndex = findMissing(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing materials-read-check prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing materials-read-check index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`materials-read-check-static-ok markers=${markers.length}`);
