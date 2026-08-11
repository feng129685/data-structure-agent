const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "function buildLearningSummary",
  "function renderLearningSummary",
  "renderLearningSummary(studyReport)",
  "learning-summary-card",
  "learning-summary-grid",
  "learning-summary-badge",
  "learningBrief: {",
  "学习小结",
  "闭环证据",
  "当前薄弱点",
  "下一步"
];

const serverMarkers = [
  "const learningBrief = value.learningBrief",
  "learningBrief,",
  "学习小结：${context.learningBrief.title}",
  "context.learningBrief.items.forEach",
  "!learningBrief"
];

function missingMarkers(text, markers) {
  return markers.filter((marker) => !text.includes(marker));
}

const missingPrototype = missingMarkers(prototype, htmlMarkers);
const missingIndex = missingMarkers(index, htmlMarkers);
const missingServer = missingMarkers(server, serverMarkers);

if (missingPrototype.length || missingIndex.length || missingServer.length) {
  if (missingPrototype.length) {
    console.error("Missing learning summary prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing learning summary index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing learning summary server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`learning-summary-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
