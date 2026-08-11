const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function renderTeacherAssignmentStudentStatus",
  "function buildTeacherAssignmentStudentReminder",
  "teacher-assignment-student-list",
  "teacher-assignment-student-groups",
  "teacher-assignment-student-chip",
  "aria-label=\"学生完成名单\"",
  "复制未完成提醒",
  "renderTeacherAssignmentStudentStatus(item)",
  "studentStatusSummary?.complete",
  "data-status=\"${escapeAttr(group.id)}\""
];

const serverMarkers = [
  "const studentStatus = targetSnapshots.map",
  "status: \"not-started\"",
  "statusLabel: \"未开始\"",
  "status: percent >= 100 ? \"complete\" : \"in-progress\"",
  "missingSteps",
  "studentStatus: studentStatus.slice(0, 24)",
  "studentStatusSummary",
  "complete: studentStatus.filter",
  "inProgress: studentStatus.filter",
  "notStarted: studentStatus.filter"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing teacher assignment student status HTML markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher assignment student status server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-assignment-student-status-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
