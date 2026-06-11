const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "server.js"), "utf8");

const htmlMarkers = [
  "function buildTeacherRescueAssignmentTemplate",
  "个人补救任务：",
  "读1句定义",
  "看1步变化",
  "跑1个例子",
  "交1句复盘",
  "teacher-assignment-rescue-action",
  "data-teacher-rescue-assignment",
  "data-teacher-rescue-student",
  "补救任务",
  "const progressRows = Array.isArray(teacherOverviewCache?.assignmentProgress)",
  "await publishTeacherAssignmentToServer(template, teacherOverviewCache || {})",
  "补救任务发布失败"
];

const serverMarkers = [
  "target_student_ids TEXT NOT NULL DEFAULT '[]'",
  "targetStudentIds: parseTargetStudentIds",
  ".filter((assignment) => isAssignmentVisibleToStudent(assignment, user.userId))"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing teacher rescue assignment HTML markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher rescue assignment server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-rescue-assignment-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
