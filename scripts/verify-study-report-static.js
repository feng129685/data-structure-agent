const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "..", "frontend", "prototype.html"), "utf8");

const markers = [
  "function buildStudyReport",
  "function buildStudyReportText",
  "async function copyStudyReport",
  "function handleStudyReportAction",
  "study-report-card",
  "study-report-grid",
  "study-report-panels",
  "data-study-report-action",
  "复习薄弱点",
  "继续章节",
  "做针对小测",
  "复制报告",
  "整体掌握度：${report.averagePercent}%",
  "activeChapterCount",
  "weakCount",
  "totalMessages",
  "handleWeakMemoryAction(\"review\"",
  "els.promptInput.value = `请围绕「${report.focusTopic}」出 3 道针对性小测题",
  "container.querySelectorAll(\"[data-study-report-action]\")"
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing study-report markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`study-report-static-ok markers=${markers.length}`);
