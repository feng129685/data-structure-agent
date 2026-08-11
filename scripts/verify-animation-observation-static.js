const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "frontend", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  "animation-observe-quiz",
  "animation-observe-option",
  "function buildAnimationObservationQuiz",
  "function renderAnimationObservationQuiz",
  "function handleAnimationObservationAnswer",
  "function buildAnimationObservationReviewPrompt",
  "function saveAnimationObservationEvidence",
  "function handleAnimationObservationReview",
  "data-animation-observe-answer",
  "data-animation-observe-save",
  "data-animation-observe-review",
  "animation-observe-save",
  "animation-observe-actions",
  "animation-observe-review",
  "data-animation-observe-topic",
  "data-animation-observe-reason",
  "card.dataset.animationObservePicked",
  "card.dataset.animationObserveCorrectText",
  "${renderAnimationObservationQuiz(loop)}",
  "recordWeakPoint({",
  "source: \"\u52a8\u753b\u89c2\u5bdf\u9898\"",
  "action: \"animation-observe-evidence\"",
  "action: \"animation-observe-review\"",
  "tag: \"\u95ed\u73af\u8bc1\u636e\"",
  "evidence: `\u52a8\u753b\u89c2\u5bdf\u9898\uff1a${result} \u00b7 ${reason}`",
  "scheduleLearningSnapshotSync(400)",
  "markLearningStep(\"quiz\", state.currentScenario)",
  "markLearningStep(\"animate\", scenario)",
  "markLearningStep(\"ask\", state.currentScenario)",
  "trackAchievement(\"quizAnswered\")",
  "handleAnimationObservationAnswer(button)",
  "saveAnimationObservationEvidence(button)",
  "handleAnimationObservationReview(button)",
  "[data-animation-followup], [data-animation-quiz], [data-animation-observe-answer], [data-animation-observe-save], [data-animation-observe-review]",
  "\u9009\u4e00\u4e2a\u7b54\u6848\uff0c\u7cfb\u7edf\u4f1a\u628a\u660e\u663e\u8584\u5f31\u70b9\u8bb0\u4e0b\u6765",
  "\u5e26\u56de\u667a\u80fd\u4f53\u590d\u76d8",
  "\u5b8c\u6210 / ${items.length}",
  "const manualDone = item.dataset.watchDone === \"true\"",
  "item.classList.toggle(\"completed\", manualDone || allStepsDone)"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing animation-observation markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`animation-observation-static-ok markers=${markers.length}`);
