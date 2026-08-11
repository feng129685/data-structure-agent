const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "frontend", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  'id="compilerReviewStrip"',
  "compilerReviewStrip: document.getElementById(\"compilerReviewStrip\")",
  "function renderCompilerReviewStrip",
  "function buildCompilerReviewPrompt",
  "function handleCompilerReviewAction",
  "data-compiler-review",
  "生成边界用例",
  "做代码小测",
  "Boolean(lastCompilerRun)",
  "handleCompilerReviewAction(\"explain\")",
  "markLearningStep(\"quiz\", state.currentScenario)",
  "markLearningStep(\"ask\", state.currentScenario)"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing compiler-review markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`compiler-review-static-ok markers=${markers.length}`);
