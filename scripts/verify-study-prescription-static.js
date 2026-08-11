const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "function buildStudyPrescription",
  "function buildStudyPrescriptionText",
  "function renderStudyPrescription",
  "async function copyStudyPrescription",
  "function sendStudyPrescriptionToCoach",
  "data-study-report-action=\"prescription\"",
  "data-study-report-action=\"copy-prescription\"",
  "data-study-prescription-step",
  "study-prescription-card",
  "study-prescription-route",
  "study-prescription-actions",
  "今日学习处方",
  "带回伴学提示词",
  "把今日学习处方带回伴学",
  "container.querySelectorAll(\"[data-study-prescription-step]\")",
  "handleTodayRouteAction(button)"
];

function missingIn(content) {
  return markers.filter((marker) => !content.includes(marker));
}

const missingPrototype = missingIn(prototype);
const missingIndex = missingIn(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing study prescription markers in prototype.html:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing study prescription markers in index.html:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`study-prescription-static-ok markers=${markers.length}`);
