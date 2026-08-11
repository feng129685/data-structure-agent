const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

const markers = [
  "profileActivePanel: \"overview\"",
  "function normalizeProfilePanel",
  "profile-tab-shell",
  "profile-panel-tabs",
  "profile-panel-section profile-panel-overview",
  "profile-panel-section profile-panel-archive",
  "profile-panel-section profile-panel-account",
  "data-profile-panel",
  "学习概览",
  "学习档案",
  "账号设置",
  "container.dataset.profilePanel = activeProfilePanel",
  "container.querySelectorAll(\"[data-profile-panel]\")",
  "state.profileActivePanel = nextPanel",
  "profileActivePanel: normalizeProfilePanel"
];

function missingIn(content) {
  return markers.filter((marker) => !content.includes(marker));
}

const missingPrototype = missingIn(prototype);
const missingIndex = missingIn(index);

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing profile panel markers in prototype.html:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing profile panel markers in index.html:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`profile-panel-tabs-static-ok markers=${markers.length}`);
