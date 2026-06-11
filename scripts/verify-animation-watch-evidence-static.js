const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "index.html"), "utf8");

const markers = [
  "function toggleAnimationWatchTask",
  "function getAnimationWatchSummary",
  "function saveAnimationWatchEvidence",
  "function sendAnimationWatchToCoach",
  "data-animation-watch-save",
  "data-animation-watch-review",
  "data-watch-title",
  "data-watch-desc",
  "button.anim-watch-item",
  "source: \"动画观察任务\"",
  "action: \"animation-watch-evidence\"",
  "action: \"animation-watch-review\"",
  "tag: \"闭环证据\"",
  "观察到：",
  "动画观察证据已保存",
  "把动画观察任务带回伴学复盘",
  "toggleAnimationWatchTask(button)",
  "saveAnimationWatchEvidence(button.dataset.animationWatchSave)",
  "sendAnimationWatchToCoach(button.dataset.animationWatchReview)"
];

function missingMarkers(text) {
  return markers.filter((marker) => !text.includes(marker));
}

const missingPrototype = missingMarkers(prototype);
const missingIndex = missingMarkers(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing animation watch evidence prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing animation watch evidence index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`animation-watch-evidence-static-ok markers=${markers.length}`);
