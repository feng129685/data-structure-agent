const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function getTeacherTaskRows",
  "const teacherTaskRows = getTeacherTaskRows(4)",
  "<h3>老师任务</h3>",
  "还没有老师任务记录",
  "任务闭环",
  "任务平均完成",
  "老师任务 ${Number(item.completedTeacherTaskCount || 0)}/${Number(item.teacherTaskCount || 0)}"
];

const serverMarkers = [
  "let teacherTaskCount = 0",
  "let completedTeacherTaskCount = 0",
  "totalTeacherTaskPercent",
  "item.snapshot.teacherTasks",
  "teacherTaskAverage",
  "completedTeacherTaskCount",
  "learning_snapshots.teacher_tasks"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing teacher task visibility HTML markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher task visibility server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-task-visibility-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
