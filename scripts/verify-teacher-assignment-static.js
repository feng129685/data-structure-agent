const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  "teacher-assignment-list",
  "teacher-assignment-card",
  "teacher-assignment-action",
  "function buildTeacherAssignmentTemplates",
  "function renderTeacherAssignmentTemplates",
  "renderTeacherAssignmentTemplates(overview)",
  "data-teacher-assignment",
  "data-teacher-assignment-text",
  "copyTeacherFollowup(button.dataset.teacherAssignmentText)",
  "\u590d\u4e60\u4efb\u52a1\u6a21\u677f",
  "\u590d\u5236\u4efb\u52a1\u6a21\u677f",
  "\u8d44\u6599\u9605\u8bfb\u3001\u4f34\u5b66\u8ffd\u95ee\u3001\u52a8\u753b\u89c2\u5bdf\u9898\u3001C \u4ee3\u7801\u5b9e\u9a8c\u548c\u590d\u76d8\u5c0f\u6d4b",
  "\u751f\u6210\u8bfe\u5802\u52a8\u753b",
  "\u3010\u6570\u636e\u7ed3\u6784\u8bfe\u540e\u8865\u9f50\u4efb\u52a1\u3011",
  "\u3010\u591a\u667a\u80fd\u4f53\u8bfe\u5802\u8ba8\u8bba\u4efb\u52a1\u3011"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing teacher-assignment markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`teacher-assignment-static-ok markers=${markers.length}`);
