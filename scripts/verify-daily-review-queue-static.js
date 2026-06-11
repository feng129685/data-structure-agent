const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const files = ["prototype.html", "index.html"];

const markers = [
  "daily-review-queue",
  "daily-review-item-head",
  "daily-review-meta",
  "daily-review-action",
  "daily-review-chip priority",
  "function buildDailyReviewQueue",
  "function getDailyReviewQueueItem",
  "function renderDailyReviewQueue",
  "function handleReviewQueueAction",
  "function saveDailyReviewQueueEvidence",
  "data-review-queue",
  "data-review-save",
  "data-review-action",
  "今日复习队列",
  "today review",
  "保存证据",
  "复习证据已保存到学习报告",
  "source: \"今日复习队列\"",
  "action: \"daily-review-evidence\"",
  "tag: \"闭环证据\"",
  "间隔复习：${dueCount} 题待处理",
  "handleWeakMemoryAction(\"review\", scenarioId, topic)",
  "handleLearningStepAction(button.dataset.reviewStep || \"ask\", scenarioId)",
  "startReviewSession();",
  "renderDailyReviewQueue(scenarioId)",
  "handleReviewQueueAction(button)",
  "saveDailyReviewQueueEvidence(button)"
];

const failures = [];

for (const file of files) {
  const html = fs.readFileSync(path.join(root, file), "utf8");
  const missing = markers.filter((marker) => !html.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing daily-review-queue markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`daily-review-queue-static-ok files=${files.length} markers=${markers.length}`);
