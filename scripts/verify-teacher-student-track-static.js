const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function renderTeacherStudentTrack",
  "teacher-student-detail",
  "teacher-student-brief",
  "teacher-student-brief-grid",
  "teacher-student-brief-chip",
  "学生学习小结",
  "学习小结",
  "闭环证据",
  "当前薄弱点",
  "teacher-student-track",
  "查看学习轨迹",
  "弱点",
  "最近复盘",
  "老师任务",
  "renderTeacherStudentTrack(item)",
  "function buildTeacherStudentAdvice",
  "复制辅导建议",
  "data-teacher-student-advice",
  "teacherStudentAdvice",
  "teacher-student-advice"
];

const serverMarkers = [
  "weakSummary",
  "recentReviews",
  "taskSummary",
  "const learningBrief = {",
  "evidenceReviews",
  "learningBrief,",
  "studentReviews.slice(0, 3)",
  "getTeacherTaskPercent(task)"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingIndex = htmlMarkers.filter((marker) => !index.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingIndex.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing teacher student track prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher student track index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher student track server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-student-track-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
