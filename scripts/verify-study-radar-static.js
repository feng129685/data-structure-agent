const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "index.html"), "utf8");

const markers = [
  "function buildStudyRadar",
  "const studyRadar = buildStudyRadar(studyReport)",
  "study-radar",
  "学习雷达",
  "study-radar-meter",
  "data-profile-weak-action",
  "data-profile-weak-scenario",
  "data-profile-weak-topic",
  "profile-weak-action",
  "profile-weak-item ${diagnosis.priority ? \"priority\" : \"\"}",
  "button.dataset.profileWeakAction",
  "handleWeakMemoryAction("
];

const missingPrototype = markers.filter((marker) => !prototype.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing study radar prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing study radar index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`study-radar-static-ok markers=${markers.length}`);
