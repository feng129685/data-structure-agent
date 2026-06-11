const fs = require("fs");
const path = require("path");
const vm = require("vm");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const requiredMarkers = [
  "anim-watch-card",
  "anim-watch-item",
  "function getAnimationWatchTasks",
  "function renderAnimationWatchCard",
  "function explainAnimationStep",
  "function updateAnimationWatchCard",
  "data-anim-explain",
  "讲解当前步",
  "id=\"guestAnimationBtn\"",
  "已进入动画实验室",
  "标准动画",
  "updateAnimationWatchCard(animId, currentStep)"
];

const missing = requiredMarkers.filter((marker) => !html.includes(marker));
if (missing.length) {
  console.error("Missing animation watch markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

const start = html.indexOf("function getAnimationWatchTasks");
const end = html.indexOf("function handleAnimationLearningLoopAction", start);
if (start < 0 || end < 0) {
  console.error("Could not locate animation watch source.");
  process.exit(1);
}

const source = html.slice(start, end);
const sandbox = {
  console,
  escapeHtml(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  },
  escapeAttr(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }
};

vm.createContext(sandbox);
vm.runInContext(source, sandbox);

const cases = [
  { type: "stack", expect: "top" },
  { type: "queue", expect: "FIFO" },
  { type: "list", expect: "head" },
  { type: "tree", expect: "遍历" },
  { type: "heap", expect: "堆序" },
  { type: "hash", expect: "key" },
  { type: "array", expect: "下标" }
];

for (const item of cases) {
  const tasks = sandbox.getAnimationWatchTasks(item.type);
  if (!Array.isArray(tasks) || tasks.length < 3) {
    console.error(`Expected ${item.type} to have at least 3 watch tasks.`);
    process.exit(1);
  }
  const text = tasks.map((task) => `${task.title} ${task.desc}`).join("\n");
  if (!text.includes(item.expect)) {
    console.error(`Expected ${item.type} watch tasks to mention ${item.expect}.`);
    console.error(text);
    process.exit(1);
  }
  const card = sandbox.renderAnimationWatchCard({ type: item.type }, `anim-${item.type}`);
  if (!card.includes("anim-watch-card") || !card.includes("data-watch-index") || !card.includes("data-animation-watch-save")) {
    console.error(`Expected ${item.type} watch card markup.`);
    process.exit(1);
  }
}

console.log(`animation-watch-static-ok cases=${cases.length}`);
