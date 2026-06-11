const fs = require("fs");
const path = require("path");
const vm = require("vm");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const requiredMarkers = [
  "function buildAnimationLearningLoop",
  "function renderAnimationLearningLoop",
  "function handleAnimationLearningLoopAction",
  "function attachAnimationLearningLoopListeners",
  "function bindAnimationLearningLoopButtons",
  "data-animation-followup",
  "data-animation-quiz",
  "animation-learning-loop"
];

const missing = requiredMarkers.filter((marker) => !html.includes(marker));
if (missing.length) {
  console.error("Missing animation loop markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

const start = html.indexOf("function buildAnimationLearningLoop");
const end = html.indexOf("function renderAnimationLearningLoop", start);
if (start < 0 || end < 0) {
  console.error("Could not locate buildAnimationLearningLoop source.");
  process.exit(1);
}

const source = html.slice(start, end);
const sandbox = {
  console,
  getAnimationTypeLabel(type) {
    const labels = {
      stack: "\u6808",
      list: "\u94fe\u8868",
      tree: "\u4e8c\u53c9\u6811",
      queue: "\u961f\u5217",
      heap: "\u5806",
      hash: "\u54c8\u5e0c\u8868",
      array: "\u6570\u7ec4"
    };
    return labels[type] || "\u6570\u636e\u7ed3\u6784";
  }
};

vm.createContext(sandbox);
vm.runInContext(source, sandbox);

const cases = [
  {
    type: "list",
    title: "\u94fe\u8868\u5934\u63d2\u6cd5",
    expectText: "\u6307\u9488",
    quizText: "\u94fe\u8868"
  },
  {
    type: "stack",
    title: "\u6808\u7684 push / pop",
    expectText: "top",
    quizText: "\u6808"
  },
  {
    type: "tree",
    title: "\u4e8c\u53c9\u6811\u5c42\u5e8f\u904d\u5386",
    expectText: "\u961f\u5217",
    quizText: "\u4e8c\u53c9\u6811"
  }
];

for (const item of cases) {
  const loop = sandbox.buildAnimationLearningLoop({ type: item.type, title: item.title, steps: [] });
  const promptsText = loop.prompts.map((prompt) => prompt.text).join("\n");
  if (!loop || loop.type !== item.type || !Array.isArray(loop.prompts) || loop.prompts.length < 2) {
    console.error("Invalid learning loop:", item.type, loop);
    process.exit(1);
  }
  if (!promptsText.includes(item.expectText)) {
    console.error(`Expected ${item.type} prompts to mention ${item.expectText}.`);
    console.error(promptsText);
    process.exit(1);
  }
  if (!String(loop.quizPrompt || "").includes(item.quizText)) {
    console.error(`Expected ${item.type} quiz prompt to mention ${item.quizText}.`);
    console.error(loop.quizPrompt);
    process.exit(1);
  }
}

console.log(`animation-loop-static-ok cases=${cases.length}`);
