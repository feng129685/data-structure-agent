const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const files = ["prototype.html", "index.html"];

const markers = [
  "home-continuity",
  "data-home-continuity",
  "id=\"homeLearningLoop\"",
  "id=\"homeWeakMemory\"",
  "home-resume-head",
  "home-resume-next",
  "home-resume-actions",
  "home-weak-spot",
  "const nextStep = firstTodo ||",
  "const firstWeakItem = weakItems[0]",
  "els.homeWeakMemory.querySelectorAll(\"[data-learning-step]\")"
];

const failures = [];

for (const file of files) {
  const text = fs.readFileSync(path.join(root, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  if (missing.length) failures.push({ file, missing });
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing home continuity markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`home-continuity-static-ok files=${files.length} markers=${markers.length}`);
