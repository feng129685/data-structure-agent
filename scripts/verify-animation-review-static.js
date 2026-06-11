const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  'id="animationReviewStrip"',
  "animationReviewStrip: document.getElementById(\"animationReviewStrip\")",
  "function renderAnimationReviewStrip",
  "function buildAnimationReviewCard",
  "function saveAnimationReviewCard",
  "function buildAnimationReviewPrompt",
  "function handleAnimationReviewAction",
  "data-animation-review",
  "data-animation-review=\"save\"",
  "保存复盘卡",
  "source: \"动画复盘卡\"",
  "action: \"animation-review-card\"",
  "动画复盘卡已保存",
  "复盘步骤",
  "出一道练习",
  "做 3 题小测",
  "看完动画后继续练一下",
  "renderAnimationBubble(safeData, \"lab\", { prompts: [] })",
  "handleAnimationReviewAction(button.dataset.animationReview)",
  "saveAnimationReviewCard(state.activeAnimationData)",
  "markLearningStep(\"animate\", card.scenario)",
  "markLearningStep(\"quiz\", state.currentScenario)",
  "markLearningStep(\"ask\", state.currentScenario)"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing animation-review markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`animation-review-static-ok markers=${markers.length}`);
