const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function buildLayeredCoachingGroups",
  "function renderTeacherLayeredCoaching",
  "renderTeacherLayeredCoaching(overview)",
  "teacher-layered-list",
  "teacher-layered-card",
  "teacher-layered-count",
  "data-teacher-layered-advice",
  "data-teacher-layered-publish",
  "teacherLayeredAdvice",
  "teacherLayeredPublish",
  "group.assignment",
  "publishTeacherAssignmentToServer(group.assignment",
  "分层辅导建议",
  "发布${escapeHtml(group.label)}任务",
  "已形成闭环证据",
  "有进度但复盘不足",
  "需要教师重点跟进"
];

const serverMarkers = [
  "learningBrief,",
  "evidenceReviews",
  "teacherTaskAverage",
  "completedTaskCount"
];

function missingMarkers(text, markers) {
  return markers.filter((marker) => !text.includes(marker));
}

const missingPrototype = missingMarkers(prototype, htmlMarkers);
const missingIndex = missingMarkers(index, htmlMarkers);
const missingServer = missingMarkers(server, serverMarkers);

if (missingPrototype.length || missingIndex.length || missingServer.length) {
  if (missingPrototype.length) {
    console.error("Missing teacher layered coaching prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher layered coaching index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing teacher layered coaching server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-layered-coaching-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
