const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "server.js"), "utf8");

const htmlMarkers = [
  "function maybeRecordTeacherRescueCompletion",
  "isTeacherRescueTask(task)",
  "progress?.complete",
  "action === \"teacher-rescue-complete\"",
  "source: \"个人补救任务\"",
  "补救闭环",
  "tag: \"闭环证据\"",
  "补救路径：",
  "scheduleLearningSnapshotSync(400)",
  "maybeRecordTeacherRescueCompletion(activeTask, progressAfter, changed)"
];

const serverMarkers = [
  "evidenceReviews = studentReviews.filter",
  "note.tag === \"闭环证据\" || note.evidence",
  "recentReviewNotes.push",
  "evidence: note.evidence"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing rescue completion evidence HTML markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing rescue completion evidence server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`rescue-completion-evidence-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
