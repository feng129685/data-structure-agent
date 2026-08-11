const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  "teacher-task-evidence-strip",
  "teacher-task-evidence-chip",
  "button type=\"button\" class=\"teacher-task-evidence-chip\"",
  "data-has-evidence",
  "data-teacher-task-action=\"${escapeAttr(item.id)}\"",
  "data-teacher-task-scenario=\"${escapeAttr(task.scenario)}\"",
  "data-teacher-task-topic=\"${escapeAttr(task.topic)}\"",
  "data-teacher-task-id=\"${escapeAttr(task.id || \"\")}\"",
  "aria-pressed=\"${item.hasEvidence ? \"true\" : \"false\"}\"",
  "function buildTeacherTaskEvidenceStatus",
  "function renderTeacherTaskEvidenceStatus",
  "teacher-task-${id}-evidence",
  "renderTeacherTaskEvidenceStatus(task)",
  "老师任务证据状态",
  "已有 · ${item.label}",
  "待补 · ${item.label}",
  "待补一条可追踪证据"
];

const failures = [];
for (const file of files) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing student teacher task evidence status markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`student-teacher-task-evidence-status-static-ok files=${files.length} markers=${markers.length}`);
