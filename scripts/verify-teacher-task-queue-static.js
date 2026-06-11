const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "..", "prototype.html"), "utf8");

const markers = [
  "activeTeacherAssignmentId",
  "function getActiveTeacherAssignments",
  "function pickTeacherAssignmentForScenario",
  "function renderTeacherTaskQueue",
  "function handleTeacherAssignmentSelect",
  "teacher-task-queue",
  "teacher-task-queue-item",
  "data-teacher-assignment-select",
  "aria-current",
  "任务队列",
  "state.activeTeacherAssignmentId = assignment.id",
  "state.currentScenario = assignment.scenario",
  "handleTeacherAssignmentSelect(button)",
  "if (data.assignment?.id) state.activeTeacherAssignmentId = data.assignment.id"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing teacher task queue markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`teacher-task-queue-static-ok markers=${markers.length}`);
