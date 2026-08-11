const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  "function buildTeacherEvidenceRescueTemplate",
  "data-teacher-evidence-rescue",
  "发布证据补救",
  "teacher-student-action-row",
  "可直接把待补证据发布成这位同学的个人补救任务",
  "const teacherStudents = Array.isArray(teacherOverviewCache?.students)",
  "buildTeacherEvidenceRescueTemplate(student)",
  "await publishTeacherAssignmentToServer(template, teacherOverviewCache || {})",
  "证据补救任务发布失败",
  "证据补齐任务：",
  "个人证据补齐任务",
  "targetStudentIds"
];

const failures = [];
for (const file of files) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing teacher evidence rescue markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-evidence-rescue-static-ok files=${files.length} markers=${markers.length}`);
