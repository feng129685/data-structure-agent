const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function buildLearningSnapshotPayload",
  "const learningEvidence = buildLearningEvidenceChecklist(report)",
  "stats: {",
  "learningEvidence: {",
  "function renderTeacherStudentTrack",
  "const evidenceRows = Array.isArray(item?.learningEvidence?.rows)",
  "teacher-student-evidence-strip",
  "teacher-student-evidence-chip",
  "闭环证据缺口",
  "闭环证据还缺"
];

const serverMarkers = [
  "function normalizeLearningEvidence",
  "const learningEvidence = normalizeLearningEvidence(value.learningEvidence)",
  "learningEvidence: normalizeLearningEvidence(body.stats.learningEvidence)",
  "const learningEvidence = normalizeLearningEvidence(item.snapshot.stats?.learningEvidence)",
  "const missingEvidenceRows = learningEvidence",
  "优先补齐：",
  "learningEvidence,",
  "missingEvidenceRows,"
];

function reportMissing(label, markers, source) {
  const missing = markers.filter((marker) => !source.includes(marker));
  if (missing.length) {
    console.error(`Missing ${label} markers:`);
    for (const marker of missing) console.error(`- ${marker}`);
  }
  return missing.length;
}

const missingCount =
  reportMissing("prototype", htmlMarkers, html) +
  reportMissing("index", htmlMarkers, index) +
  reportMissing("server", serverMarkers, server);

if (missingCount) process.exit(1);

console.log(`teacher-evidence-gap-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
