const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const files = ["prototype.html", "index.html"];

const markers = [
  "function hasLearningConfusionSignal",
  "function firstLearningSentence",
  "function recordCoachChatLearningTrace",
  "source: \"伴学问答\"",
  "action: \"coach-auto-trace\"",
  "tag: \"闭环证据\"",
  "const autoTraceRecorded = recordCoachChatLearningTrace({ prompt, reply, inferredWeak, attachments })",
  "if (autoTraceRecorded) scheduleLearningSnapshotSync(400)",
  "hasLearningConfusionSignal(prompt)",
  "learningContext: buildLearningContextPayload()"
];

const failures = [];

for (const file of files) {
  const text = fs.readFileSync(path.join(frontendRoot, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing chat learning trace markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`chat-learning-trace-static-ok files=${files.length} markers=${markers.length}`);
