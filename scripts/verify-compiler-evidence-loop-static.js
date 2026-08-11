const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  "compiler-evidence-card",
  "data-compiler-evidence-card",
  "compiler-evidence-status",
  "compiler-evidence-actions",
  "function getCompilerBoundaryHint",
  "function getCompilerRunSummary",
  "function renderCompilerEvidenceCard",
  "保存实验证据",
  "带去伴学复盘",
  "生成边界用例",
  "{ id: \"evidence-review\", label: \"带去伴学复盘\", primary: true }",
  "if (kind === \"evidence-review\")",
  "系统建议我补测",
  "action: `compiler-${safeKind}`",
  "tag: \"闭环证据\"",
  "markTeacherTaskStep(\"compiler\"",
  "renderCompilerEvidenceCard();"
];

const failures = [];

for (const file of files) {
  const html = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !html.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing compiler evidence loop markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`compiler-evidence-loop-static-ok files=${files.length} markers=${markers.length}`);
