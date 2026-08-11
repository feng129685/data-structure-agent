const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function renderTeacherReviewActivity",
  "overview?.recentReviewNotes",
  "overview?.reviewSourceBreakdown",
  "复盘活跃度",
  "teacher-review-panel",
  "teacher-review-score",
  "teacher-review-list",
  "renderTeacherReviewActivity(overview)",
  "推动复盘闭环",
  "reviewStudentCount",
  "复盘 ${Number(item.reviewNoteCount || 0)} 次"
];

const serverMarkers = [
  "reviewStudentIds",
  "reviewSourceMap",
  "recentReviewNotes",
  "reviewNoteCount",
  "reviewStudentCount",
  "reviewSourceBreakdown",
  "stats?.reviewNotes"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingIndex = htmlMarkers.filter((marker) => !index.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingIndex.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing teacher review activity prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher review activity index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher review activity server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-review-activity-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
