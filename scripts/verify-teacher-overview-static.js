const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const serverMarkers = [
  "const TEACHER_EMAILS = new Set",
  "const ALLOW_FIRST_USER_TEACHER = parseBooleanEnv",
  "function isTeacherEmail",
  "function isTeacherUser",
  "if (!ALLOW_FIRST_USER_TEACHER) return false",
  "function requireTeacher",
  "function buildTeacherOverview",
  "function handleGetTeacherOverview",
  "\"/api/teacher/overview\"",
  "learning_snapshots.learning_progress",
  "maskEmail(row.email)"
];

const frontendMarkers = [
  "data-view=\"teacher\" id=\"teacherNavBtn\" hidden",
  "body:not([data-view=\"teacher\"]) .teacher-view",
  "teacher-view\" id=\"teacherView\"",
  "\"teacher\"",
  "teacher: {",
  "teacherView: document.getElementById(\"teacherView\")",
  "loadTeacherOverviewFromServer",
  "renderTeacherOverviewData",
  "async function renderTeacherOverview",
  "if (state.activeView === \"teacher\") renderTeacherOverview()",
  "currentUser?.isTeacher",
  "data-teacher-refresh",
  "教师概览"
];

const missingServer = serverMarkers.filter((marker) => !server.includes(marker));
const missingFrontend = frontendMarkers.filter((marker) => !html.includes(marker));

if (missingServer.length || missingFrontend.length) {
  if (missingServer.length) {
    console.error("Missing server teacher-overview markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  if (missingFrontend.length) {
    console.error("Missing frontend teacher-overview markers:");
    for (const marker of missingFrontend) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-overview-static-ok server=${serverMarkers.length} frontend=${frontendMarkers.length}`);
