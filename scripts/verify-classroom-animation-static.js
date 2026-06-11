const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  "data-classroom-review=\"animate\"",
  "classroom-review-btn visual",
  "\u751f\u6210\u8bfe\u5802\u52a8\u753b",
  "function buildClassroomAnimationPrompt",
  "async function handleClassroomAnimationAction",
  "handleClassroomAnimationAction();",
  "await sendAnimationRequest(scenarioId, {",
  "prompt: buildClassroomAnimationPrompt(scenarioId)",
  "modeLabel: \"\u8bfe\u5802\u52a8\u753b\"",
  "source: \"classroom\"",
  "async function sendAnimationRequest(scenarioOverride, options = {})",
  "const animPrompt = options.prompt || typeMap[scenario]",
  "animationSource: options.source || \"coach\"",
  "options.source === \"classroom\"",
  "finally {\n        isSending = false;"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing classroom-animation markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`classroom-animation-static-ok markers=${markers.length}`);
