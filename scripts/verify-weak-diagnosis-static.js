const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "..", "frontend", "prototype.html"), "utf8");

const markers = [
  "function getWeakPointDiagnosis",
  "function buildWeakPointPrompt",
  "function handleWeakMemoryAction",
  "薄弱点诊断",
  "继续提问、做小测或运行代码后，这里会自动生成诊断和复习入口。",
  "weak-memory-item ${diagnosis.priority ? \"priority\" : \"\"}",
  "weak-memory-meta",
  "weak-memory-actions",
  "data-weak-action",
  "data-weak-scenario",
  "data-weak-topic",
  "button.dataset.weakAction",
  "button.dataset.weakScenario",
  "button.dataset.weakTopic",
  "openMaterialsChapter(diagnosis.scenario)",
  "buildFallbackAnimationData(diagnosis.scenario)",
  "openCompilerFromOrchestrator(diagnosis.topic)",
  "els.promptInput.value = buildWeakPointPrompt(item)"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing weak-diagnosis markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`weak-diagnosis-static-ok markers=${markers.length}`);
