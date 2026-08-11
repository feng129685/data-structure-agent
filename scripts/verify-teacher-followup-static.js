const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "..", "frontend", "prototype.html"), "utf8");

const markers = [
  "teacher-followup-list",
  "teacher-followup-card",
  "teacher-followup-action",
  "function buildTeacherFollowupQueue",
  "function renderTeacherFollowupQueue",
  "async function copyTeacherFollowup",
  "data-teacher-followup",
  "data-teacher-followup-text",
  "教学跟进队列",
  "集中讲解：${topWeak.topic}",
  "补齐章节：${scenarioChapterLabel[lowChapter.scenario] || lowChapter.scenario}",
  "关注学生：${lowStudent.email}",
  "renderTeacherFollowupQueue(overview)",
  "copyTeacherFollowup(button.dataset.teacherFollowupText)"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing teacher-followup markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`teacher-followup-static-ok markers=${markers.length}`);
