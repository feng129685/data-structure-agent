const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const files = ["prototype.html", "index.html"];

const markers = [
  "function buildStudyHandoff",
  "function buildStudyHandoffText",
  "function renderStudyHandoffCard",
  "async function copyStudyHandoff",
  "study-handoff-card",
  "study-handoff-grid",
  "study-handoff-evidence",
  "data-study-report-action=\"copy-handoff\"",
  "给老师的学习交付摘要",
  "可提交给老师",
  "复制给老师",
  "buildStudyHandoff(report)",
  "copyStudyHandoff(report)",
  "if (action === \"copy-handoff\")"
];

const failures = [];

for (const file of files) {
  const text = fs.readFileSync(path.join(root, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing study handoff markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`study-handoff-static-ok files=${files.length} markers=${markers.length}`);
