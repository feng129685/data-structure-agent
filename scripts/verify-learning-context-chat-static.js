const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function buildLearningContextPayload",
  "learningContext: buildLearningContextPayload()",
  "const todayRoute = buildTodayReviewRoute",
  "todayRoute: {",
  "steps: todayRoute.steps.map",
  "radarTitle: radar.title",
  "weakPoints,",
  "progress"
];

const serverMarkers = [
  "function normalizeLearningContext",
  "function formatLearningContext",
  "const learningContext = normalizeLearningContext(body.learningContext)",
  "todayRoute",
  "context.todayRoute",
  "今日复习路线",
  "学生学习记忆",
  "如果课程上下文里提供了学生学习记忆",
  "章节进度和今日复习路线",
  "重点薄弱点：",
  "近期章节进度："
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingIndex = htmlMarkers.filter((marker) => !index.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingIndex.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing learning context prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing learning context index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing learning context server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`learning-context-chat-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
