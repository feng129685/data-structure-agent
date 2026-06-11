const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "index.html"), "utf8");

const markers = [
  "function buildAnimationReviewCard",
  "function saveAnimationReviewCard",
  "data-animation-review=\"save\"",
  "保存复盘卡",
  "source: \"动画复盘卡\"",
  "action: \"animation-review-card\"",
  "markLearningStep(\"animate\", card.scenario)",
  "renderHomeLearningLoop();",
  "renderProfile();",
  "renderAnimationReviewStrip(animationData);",
  "动画复盘卡已保存",
  "saveAnimationReviewCard(state.activeAnimationData)",
  "state.recentAction = `保存动画复盘卡：${card.label}`"
];

function missingIn(html) {
  return markers.filter((marker) => !html.includes(marker));
}

const missingPrototype = missingIn(prototype);
const missingIndex = missingIn(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing animation-review-card prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing animation-review-card index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`animation-review-card-static-ok markers=${markers.length}`);
