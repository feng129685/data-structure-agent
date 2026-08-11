const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "teacher-assignment-evidence-chip",
  "student.rescueEvidence",
  "已形成补救闭环证据",
  "student.rescueEvidence.tag || \"闭环证据\""
];

const serverMarkers = [
  "const rescueEvidence = reviewNotes.find",
  "note.action === \"teacher-rescue-complete\"",
  "String(note.evidence || \"\").includes(assignment.id || \"\")",
  "rescueEvidence: rescueEvidence ?",
  "tag: rescueEvidence.tag || \"闭环证据\""
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing teacher rescue evidence status HTML markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher rescue evidence status server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-rescue-evidence-status-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
