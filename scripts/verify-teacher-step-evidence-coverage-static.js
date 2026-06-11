const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const htmlFiles = ["prototype.html", "index.html"];
const server = fs.readFileSync(path.join(root, "server.js"), "utf8");

const htmlMarkers = [
  "teacher-assignment-step-evidence-rate",
  "const evidenceCount = Number(step.evidenceCount || 0)",
  "const evidencePercent = Math.max(0, Math.min(100, Number(step.evidencePercent || 0)))",
  "const evidenceText = started ? `证据 ${evidenceCount}/${started} · ${evidencePercent}%` : \"证据 0/0\"",
  "${escapeHtml(evidenceText)}"
];

const serverMarkers = [
  "step.evidenceCount = studentStatus.filter",
  "student.stepEvidence.some((evidence) => evidence.id === step.id)",
  "evidencePercent: startedCount ? Math.round((Number(step.evidenceCount || 0) / startedCount) * 100) : 0",
  "evidenceCount"
];

const failures = [];
for (const file of htmlFiles) {
  const text = fs.readFileSync(path.join(root, file), "utf8");
  const missing = htmlMarkers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));
if (missingServer.length) failures.push({ file: "server.js", missing: missingServer });

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing teacher step evidence coverage markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-step-evidence-coverage-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
