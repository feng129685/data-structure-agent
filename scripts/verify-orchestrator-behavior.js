const fs = require("fs");
const path = require("path");
const vm = require("vm");

const htmlPath = path.join(__dirname, "..", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const start = html.indexOf("function buildLearningOrchestratorPlan");
const end = html.indexOf("function renderLearningOrchestratorCard", start);

if (start < 0 || end < 0) {
  console.error("Could not locate buildLearningOrchestratorPlan source.");
  process.exit(1);
}

const source = html.slice(start, end);
const sandbox = {
  console,
  scenarioData: {
    choose: { chapter: "\u9009\u7ed3\u6784\u5efa\u8bae" },
    list: { chapter: "\u94fe\u8868" },
    stack: { chapter: "\u6808" }
  },
  scenarioChapterLabel: {
    choose: "\u9009\u7ed3\u6784\u5efa\u8bae",
    list: "\u94fe\u8868",
    stack: "\u6808"
  },
  state: { currentScenario: "choose" },
  detectScenarioFromMessage(message) {
    const text = String(message || "");
    if (text.includes("\u94fe\u8868") || text.includes("head") || text.includes("next")) return "list";
    if (text.includes("\u6808")) return "stack";
    return null;
  }
};

vm.createContext(sandbox);
vm.runInContext(source, sandbox);

const cases = [
  {
    name: "animation-first for pointer process",
    question: "\u94fe\u8868\u5934\u63d2\u6cd5\u4e3a\u4ec0\u4e48\u8981\u5148\u8ba9\u65b0\u8282\u70b9\u7684 next \u6307\u5411\u539f head\uff1f\u80fd\u4e0d\u80fd\u7528\u52a8\u753b\u7406\u89e3\uff1f",
    answer: "\u6700\u540e\u4e5f\u53ef\u4ee5\u7528 C \u4ee3\u7801\u9a8c\u8bc1\u3002",
    expectedPrimary: "animation"
  },
  {
    name: "compiler-first for C code errors",
    question: "\u8fd9\u6bb5 C \u8bed\u8a00\u94fe\u8868\u4ee3\u7801\u4e3a\u4ec0\u4e48\u7f16\u8bd1\u62a5\u9519\uff1f",
    answer: "\u53ef\u4ee5\u770b\u72b6\u6001\u53d8\u5316\u3002",
    expectedPrimary: "compiler"
  },
  {
    name: "materials-first for review intent",
    question: "\u6211\u60f3\u590d\u4e60\u6808\u8fd9\u4e00\u7ae0\u7684\u77e5\u8bc6\u70b9\u548c\u8003\u70b9",
    answer: "",
    expectedPrimary: "materials"
  }
];

const results = cases.map((item) => {
  const plan = sandbox.buildLearningOrchestratorPlan(item.question, item.answer);
  return {
    name: item.name,
    primary: plan.primary,
    firstAction: plan.actions[0] && plan.actions[0].id,
    expectedPrimary: item.expectedPrimary
  };
});

for (const result of results) {
  if (result.primary !== result.expectedPrimary || result.firstAction !== result.expectedPrimary) {
    console.error("Unexpected orchestrator behavior:");
    console.error(JSON.stringify(results, null, 2));
    process.exit(1);
  }
}

console.log(`orchestrator-behavior-ok cases=${results.length}`);
