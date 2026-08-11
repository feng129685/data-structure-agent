const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const requiredMarkers = [
  "border-radius: 999px;",
  "background: rgba(255, 253, 248, 0.68);",
  "box-shadow: none;",
  "animation: microSoftRise 180ms",
  "<span class=\"learning-orchestrator-mark\" aria-hidden=\"true\">1</span>",
  "<p class=\"learning-orchestrator-title\">课后一步</p>",
  "actions: [recommendation.action]"
];

const forbiddenMarkers = [
  "linear-gradient(135deg, rgba(255, 255, 255, 0.58), rgba(255, 255, 255, 0.14))",
  "<span class=\"learning-orchestrator-mark\" aria-hidden=\"true\">→</span>",
  "box-shadow: 0 10px 24px rgba(74, 55, 40, 0.055)"
];

const failures = [];

for (const file of files) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = requiredMarkers.filter((marker) => !text.includes(marker));
  const presentForbidden = forbiddenMarkers.filter((marker) => text.includes(marker));
  if (missing.length || presentForbidden.length) {
    failures.push({ file, missing, presentForbidden });
  }
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Orchestrator quiet UI check failed in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- missing ${marker}`);
    for (const marker of failure.presentForbidden) console.error(`- forbidden ${marker}`);
  }
  process.exit(1);
}

console.log(`orchestrator-quiet-ui-static-ok files=${files.length} required=${requiredMarkers.length}`);
