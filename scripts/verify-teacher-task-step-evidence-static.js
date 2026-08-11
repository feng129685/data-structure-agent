const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  "function recordTeacherTaskStepEvidence",
  "teacher-task-${action}-evidence",
  "老师任务资料",
  "老师任务动画",
  "老师任务 C 实验",
  "老师任务复盘",
  "tag: \"闭环证据\"",
  "markLearningStep(actionMeta.learningStep, scenario)",
  "recordTeacherTaskStepEvidence({ action, scenarioId, topic, assignmentId, changed })",
  "if (evidenceRecorded || rescueCompleted) scheduleLearningSnapshotSync(400)",
  "recordTeacherTaskStepEvidence({ action: \"coach\", scenarioId, topic, assignmentId, changed: coachChanged })",
  "C 实验：已进入代码验证",
  "伴学复盘：已提交任务复盘"
];

const failures = [];
for (const file of files) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing teacher task step evidence markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`teacher-task-step-evidence-static-ok files=${files.length} markers=${markers.length}`);
