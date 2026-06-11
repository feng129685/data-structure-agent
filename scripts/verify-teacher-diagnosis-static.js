const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  "teacher-diagnosis-card",
  "teacher-diagnosis-grid",
  "teacher-diagnosis-action",
  "function buildTeacherDiagnosis",
  "function renderTeacherDiagnosisCard",
  "renderTeacherDiagnosisCard(overview)",
  "data-teacher-diagnosis-action",
  "data-teacher-diagnosis-text",
  "copyTeacherFollowup(button.dataset.teacherDiagnosisText)",
  "\u73ed\u7ea7\u9700\u8981\u4e00\u6b21\u96c6\u4e2d\u590d\u76d8",
  "\u590d\u5236\u5e72\u9884\u5efa\u8bae",
  "\u52a8\u753b\u89c2\u5bdf\u9898",
  "riskStudents",
  "weakPoints[0]",
  "lowChapter"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing teacher-diagnosis markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`teacher-diagnosis-static-ok markers=${markers.length}`);
