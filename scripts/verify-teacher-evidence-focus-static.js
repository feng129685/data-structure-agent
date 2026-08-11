const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const htmlMarkers = [
  "function renderTeacherEvidenceFocus",
  "teacher-evidence-focus",
  "teacher-evidence-meter",
  "teacher-evidence-copy",
  "teacher-evidence-missing",
  "学生闭环证据焦点",
  "renderTeacherEvidenceFocus(item)",
  "data-tone=\"${escapeAttr(focus.tone || \"steady\")}\""
];

const serverMarkers = [
  "const evidenceFocus = (() => {",
  "tone: \"needs-evidence\"",
  "tone: \"empty\"",
  "tone: \"ready\"",
  "tone: \"steady\"",
  "missingTitles:",
  "evidenceFocus,"
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
    console.error(`Missing teacher evidence focus markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-evidence-focus-static-ok files=${files.length + 1} markers=${htmlMarkers.length + serverMarkers.length}`);
