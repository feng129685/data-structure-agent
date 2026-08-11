const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "teacherTasks: {}",
  "const teacherTaskStepIds",
  "function sanitizeTeacherTasks",
  "function mergeTeacherTasks",
  "function markTeacherTaskStep",
  "teacherTasks: sanitizeTeacherTasks(state.teacherTasks || {})",
  "state.teacherTasks = mergeTeacherTasks",
  ".teacher-task-action.done",
  ".teacher-task-badge.done",
  "每完成一步都会保存到学习记忆",
  "markTeacherTaskStep(action, scenarioId, topic, assignmentId)",
  "aria-pressed"
];

const serverMarkers = [
  "teacher_tasks TEXT NOT NULL DEFAULT '{}'",
  "ALTER TABLE learning_snapshots ADD COLUMN teacher_tasks",
  "const teacherTasks = body.teacherTasks",
  "teacherTasks: parse(row.teacher_tasks, {})",
  "SELECT learning_progress, weak_memory, teacher_tasks, report, stats, updated_at",
  "INSERT INTO learning_snapshots (user_id, learning_progress, weak_memory, teacher_tasks, report, stats, updated_at)",
  "teacher_tasks = excluded.teacher_tasks",
  "JSON.stringify(snapshot.teacherTasks)"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing teacher task progress HTML markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher task progress server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-task-progress-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
