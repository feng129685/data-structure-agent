const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  "id=\"compilerAnalyzeBtn\"",
  "let lastCompilerRun = null",
  "function updateCompilerAnalyzeButton",
  "function analyzeCompilerResultWithCoach",
  "把 C 编译器结果带回智能体分析",
  "lastCompilerRun = { code, stdin, output:",
  "els.compilerAnalyzeBtn.addEventListener(\"click\", analyzeCompilerResultWithCoach)",
  "materials-study-card",
  "materials-quick-summary",
  "data-path-step",
  "data-material-action",
  "本章怎么学",
  "下一步可以做",
  "openCompilerFromOrchestrator(scenarioData[scenarioId]?.chapter || \"\")",
  "openMaterialsChapter(scenario);",
  "<strong>${escapeHtml(action.label)}</strong>",
  "<span>${escapeHtml(action.desc || \"继续学习这个问题\")}</span>"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing learning-loop upgrade markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`learning-loop-upgrade-static-ok markers=${markers.length}`);
