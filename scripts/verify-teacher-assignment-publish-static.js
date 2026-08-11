const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "teacherAssignments: []",
  "function sanitizeTeacherAssignments",
  "async function loadAssignmentsFromServer",
  "async function publishTeacherAssignmentToServer",
  "async function archiveTeacherAssignmentOnServer",
  "await loadAssignmentsFromServer()",
  "const remoteAssignment = pickTeacherAssignmentForScenario(scenario)",
  "来自老师发布的课程任务",
  "data-teacher-assignment-publish",
  "data-teacher-assignment-archive",
  "已发布到学生首页"
];

const serverMarkers = [
  "CREATE TABLE IF NOT EXISTS teacher_assignments",
  "function normalizeTeacherAssignment",
  "function handleGetAssignments",
  "async function handleCreateTeacherAssignment",
  "function handleArchiveTeacherAssignment",
  "GET\" && pathname === \"/api/assignments\"",
  "POST\" && pathname === \"/api/teacher/assignments\"",
  "DELETE\" && teacherAssignmentMatch",
  "rowToTeacherAssignment"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing teacher assignment publish HTML markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher assignment publish server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-assignment-publish-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
