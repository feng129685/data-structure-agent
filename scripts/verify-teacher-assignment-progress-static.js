const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function renderTeacherAssignmentProgress",
  "overview?.assignmentProgress",
  "teacher-assignment-progress-list",
  "teacher-progress-bar",
  "teacher-assignment-step-breakdown",
  "teacher-assignment-step-chip",
  "teacher-assignment-step-fill",
  "teacher-assignment-reminder-action",
  "function buildTeacherAssignmentReminder",
  "data-teacher-assignment-reminder",
  "button.dataset.teacherAssignmentReminder",
  "actionMap",
  "item.stepBreakdown",
  "weakestStep",
  "data-teacher-task-id",
  "assignmentId"
];

const serverMarkers = [
  "assignmentId",
  "assignmentProgress",
  "const TEACHER_TASK_STEP_IDS",
  "const TEACHER_TASK_STEP_LABELS",
  "function getTeacherTaskPercent",
  "function buildTeacherOverview(rows, assignments = [])",
  "FROM teacher_assignments",
  "WHERE status = 'active'",
  "startedCount",
  "completedCount",
  "stepBreakdown",
  "doneCount",
  "totalStudents",
  "averagePercent: startedCount ? Math.round(totalPercent / startedCount) : 0"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingIndex = htmlMarkers.filter((marker) => !index.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingIndex.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing assignment progress prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing assignment progress index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing assignment progress server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-assignment-progress-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
