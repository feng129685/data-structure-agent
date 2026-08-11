const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const htmlMarkers = [
  "const handoff = buildStudyHandoff(report)",
  "studyHandoff: {",
  "readiness: handoff.readiness",
  "chapter: handoff.chapter",
  "evidenceStatus: handoff.learningEvidence.status",
  "evidenceLine: handoff.evidenceLine",
  "focusWeak: handoff.focusWeak ? {",
  "latestReview: handoff.latestReview ? {",
  "teacherAverage: handoff.teacherAverage",
  "learningContext: buildLearningContextPayload()"
];

const serverMarkers = [
  "function normalizeStudyHandoff",
  "const studyHandoff = normalizeStudyHandoff(value.studyHandoff)",
  "studyHandoff,",
  "!studyHandoff",
  "学习交付摘要：",
  "交付证据进度：",
  "证据明细：",
  "交付薄弱点：",
  "交付最近复盘：",
  "如果学生学习记忆里包含学习交付摘要或证据缺口"
];

const failures = [];

for (const file of files) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = htmlMarkers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));
if (missingServer.length) failures.push({ file: "backend/node/server.js", missing: missingServer });

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing learning handoff context markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`learning-handoff-context-static-ok files=${files.length + 1} markers=${htmlMarkers.length + serverMarkers.length}`);
