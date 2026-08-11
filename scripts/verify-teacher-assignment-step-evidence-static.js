const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const htmlFiles = ["prototype.html", "index.html"];
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "teacher-assignment-step-evidence",
  "teacher-assignment-step-evidence-chip",
  "任务步骤证据",
  "student.stepEvidence.slice(0, 3)",
  "证据 · ${escapeHtml(evidence.label",
  "已同步步骤证据"
];

const serverMarkers = [
  "const buildStepEvidence = (reviewNotes) => TEACHER_TASK_STEP_IDS",
  "item.action === `teacher-task-${id}-evidence`",
  "stepEvidence: buildStepEvidence(reviewNotes)",
  "label: assignmentStepLabels.get(id) || TEACHER_TASK_STEP_LABELS[id] || id",
  "evidence: note.evidence"
];

const failures = [];
for (const file of htmlFiles) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = htmlMarkers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));
if (missingServer.length) failures.push({ file: "backend/node/server.js", missing: missingServer });

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing teacher assignment step evidence markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-assignment-step-evidence-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
