const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "index.html"), "utf8");

const markers = [
  "保存实验证据",
  "function buildCompilerExperimentDetail",
  "function saveCompilerExperimentRecord",
  "source: \"C 实验记录\"",
  "action: \"compiler-save\"",
  "tag: \"闭环证据\"",
  "evidence: `代码输出：",
  "markLearningStep(\"code\", state.currentScenario)",
  "markTeacherTaskStep(\"compiler\"",
  "保存 C 实验记录到学习报告",
  "C 实验记录已保存到学习报告",
  "[\"explain\", \"cases\", \"quiz\", \"save\", \"evidence-review\"]",
  "saveCompilerExperimentRecord();"
];

const missingPrototype = markers.filter((marker) => !prototype.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missingPrototype.length || missingIndex.length) {
  if (missingPrototype.length) {
    console.error("Missing compiler experiment record prototype markers:");
    for (const marker of missingPrototype) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing compiler experiment record index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`compiler-experiment-record-static-ok markers=${markers.length}`);
