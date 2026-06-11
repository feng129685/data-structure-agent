const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "server.js"), "utf8");

const htmlMarkers = [
  "targetStudentIds: Array.isArray(template.targetStudentIds)",
  "targetStudentIds: remoteAssignment.targetStudentIds",
  "targeted: Boolean(remoteAssignment.targeted",
  "分层精准任务",
  "发布后只进入这组学生的首页任务队列",
  "精准 ${Number(item.targetStudentCount",
  "全班可见",
  "目标 ${targetCount",
  "这一层暂无学生，不需要发布任务",
  "const targetStudentIds = Array.from(new Set(group.students"
];

const serverMarkers = [
  "target_student_ids TEXT NOT NULL DEFAULT '[]'",
  "ALTER TABLE teacher_assignments ADD COLUMN target_student_ids",
  "function normalizeTargetStudentIds",
  "function isAssignmentVisibleToStudent",
  "targetStudentIds: parseTargetStudentIds",
  ".filter((assignment) => isAssignmentVisibleToStudent(assignment, user.userId))",
  "target_student_ids = excluded.target_student_ids",
  "targetStudentCount: targetSnapshots.length",
  "targeted: targetIds.length > 0",
  "totalStudents: targetSnapshots.length"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing targeted assignment HTML markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing targeted assignment server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`targeted-teacher-assignments-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
