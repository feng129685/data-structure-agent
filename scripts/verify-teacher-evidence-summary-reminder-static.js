const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  "function buildTeacherAssignmentEvidenceSummary",
  "const evidenceSummary = buildTeacherAssignmentEvidenceSummary(item)",
  "证据覆盖 ${totalEvidence}/${totalSlots} · ${evidencePercent}%",
  "${escapeHtml(evidenceSummary.label)}",
  "const weakestEvidenceStep = evidenceSummary.weakestEvidenceStep",
  "证据情况：${evidenceIntro}",
  "证据最薄",
  "当前还没有可追踪证据",
  "evidenceSummary.evidencePercent"
];

const failures = [];
for (const file of files) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing teacher evidence summary reminder markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-evidence-summary-reminder-static-ok files=${files.length} markers=${markers.length}`);
