const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "index.html"), "utf8");

const markers = [
  "teacherActivePanel: \"action\"",
  "teacherActivePanel: normalizeTeacherPanel(saved.teacherActivePanel)",
  "teacherActivePanel: state.teacherActivePanel",
  "function normalizeTeacherPanel",
  "function renderTeacherPanelShell",
  "function renderTeacherChapterWeakPanel",
  "function renderTeacherStudentsPanel",
  "function bindTeacherOverviewEvents",
  "teacher-panel-tabs",
  "teacher-panel-tab",
  "data-teacher-panel",
  "教学工作台",
  "教学行动",
  "复盘线索",
  "学生轨迹",
  "bindTeacherOverviewEvents(container)"
];

const missingPrototype = markers.filter((marker) => !prototype.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing teacher panel tab prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing teacher panel tab index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-panel-tabs-static-ok markers=${markers.length}`);
