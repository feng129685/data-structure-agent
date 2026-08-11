const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "frontend", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  "materials-review-strip",
  "materials-review-copy",
  "materials-review-actions",
  "function buildMaterialsReviewPrompt",
  "function renderMaterialsReviewStrip",
  "function handleMaterialsReviewAction",
  "data-materials-review",
  "data-materials-review-scenario",
  "openPromptFromMaterials(buildMaterialsReviewPrompt(kind, scenarioId))",
  "markLearningStep(kind === \"quiz\" ? \"quiz\" : \"ask\", scenarioId)",
  "${renderMaterialsReviewStrip(activeId)}",
  "\u751f\u6210\u672c\u7ae0\u901f\u8bb0",
  "\u505a\u672c\u7ae0\u5c0f\u6d4b",
  "\u5e26\u56de\u4f34\u5b66\u8ffd\u95ee",
  "\u8bfb\u5b8c\u672c\u7ae0\u540e\u7684\u590d\u76d8\u5165\u53e3"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing materials-review markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`materials-review-static-ok markers=${markers.length}`);
