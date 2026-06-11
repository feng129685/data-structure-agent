const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "index.html"), "utf8");

const markers = [
  "learning-complete-card",
  "function buildChapterCompletionSummary",
  "function pickNextLearningScenario",
  "function renderLearningCompleteCard",
  "async function handleLearningCompleteAction",
  "data-learning-complete-action",
  "data-learning-complete-scenario",
  "本章学习闭环完成",
  "闭环已完成",
  "复制本章复盘",
  "进入下一章",
  "source: \"闭环完成\"",
  "action: \"chapter-complete\"",
  "renderLearningCompleteCard(scenarioId)",
  "handleLearningCompleteAction(button)"
];

const missingPrototype = markers.filter((marker) => !prototype.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing learning complete card prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing learning complete card index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`learning-complete-card-static-ok markers=${markers.length}`);
