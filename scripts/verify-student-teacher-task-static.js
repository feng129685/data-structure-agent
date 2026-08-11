const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  "teacher-task-panel",
  "teacher-task-action",
  "function buildStudentTeacherTask",
  "function buildTeacherTaskReviewPrompt",
  "function buildTeacherTaskNextStep",
  "function renderTeacherTaskNextStep",
  "function renderTeacherTaskReviewCard",
  "function renderTeacherTaskPanel",
  "function handleTeacherTaskAction",
  "function handleTeacherTaskReview",
  "data-teacher-task-action",
  "data-teacher-task-review",
  "data-teacher-task-scenario",
  "data-teacher-task-topic",
  "handleTeacherTaskAction(button)",
  "handleTeacherTaskReview(button)",
  "teacher-task-review-card",
  "teacher-task-review-btn",
  "teacher-task-next",
  "teacher-task-next-action",
  "data-teacher-task-next",
  "teacher-task-review",
  "if (progress.complete || progress.done < 2) return \"\"",
  "renderTeacherTaskNextStep(task, progress)",
  "data-teacher-task-review=\"${escapeAttr(task.scenario)}\"",
  "tag: \"\u95ed\u73af\u8bc1\u636e\"",
  "\u5b8c\u6210\u8def\u5f84\uff1a",
  "\u5e26\u53bb\u4f34\u5b66\u590d\u76d8",
  "\u8001\u5e08\u5e03\u7f6e\u7684\u4efb\u52a1",
  "\u770b\u8d44\u6599",
  "\u770b\u52a8\u753b",
  "\u8dd1 C \u5b9e\u9a8c",
  "\u4ea4\u7ed9\u4f34\u5b66\u590d\u76d8",
  "openMaterialsChapter(scenarioId)",
  "openCompilerFromOrchestrator(topic)",
  "applyAnimationHandoff(buildAnimationHandoff(topic, scenarioId))"
];

const failures = [];

for (const file of files) {
  const html = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !html.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing student-teacher-task markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`student-teacher-task-static-ok files=${files.length} markers=${markers.length}`);
