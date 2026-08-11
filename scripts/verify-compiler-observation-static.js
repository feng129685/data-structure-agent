const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "compiler-observe-card",
  "compiler-observe-option",
  "compiler-observe-result",
  "function buildCompilerObservationQuiz",
  "function renderCompilerObservationQuiz",
  "function handleCompilerObservationAnswer",
  "data-compiler-observe-answer",
  "data-compiler-observe-topic",
  "C 实验观察",
  "C 实验观察题",
  "source: \"C 实验观察\"",
  "action: \"compiler-observe-evidence\"",
  "tag: \"闭环证据\"",
  "evidence: `C 实验观察题：${isCorrect ? \"答对\" : \"答错\"} · ${reason}`",
  "const pickedText = button.textContent.trim()",
  "const correctButton = card.querySelector(`[data-compiler-observe-answer=\"${correct}\"]`)",
  "scheduleLearningSnapshotSync(400)",
  "已保存到学习证据",
  "recordWeakPoint({",
  "renderCompilerObservationQuiz();",
  "handleCompilerObservationAnswer(observeButton)"
];

function missingIn(content) {
  return markers.filter((marker) => !content.includes(marker));
}

const missingPrototype = missingIn(prototype);
const missingIndex = missingIn(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing compiler observation markers in prototype.html:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing compiler observation markers in index.html:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`compiler-observation-static-ok markers=${markers.length}`);
