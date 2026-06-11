const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const files = ["prototype.html", "index.html"];

const markers = [
  "animationHandoff: null",
  "function pickAnimationOperationFromText",
  "function buildAnimationHandoff",
  "function applyAnimationHandoff",
  "pickAnimationOperationFromText(type, seed)",
  "applyAnimationHandoff(handoff, { open: false })",
  "sendAnimationRequest(scenario, { seed })",
  "sendAnimationRequest(targetScenario, { seed: userMessage })",
  "sendAnimationRequest(targetScenario, { seed: animationSeed })",
  "commitAnimationResult(animationData, handoff.type, { handoff })",
  "state.animationHandoff = handoff",
  "请优先围绕「${handoff.title}」设计步骤。",
  "观察重点：${handoff.focus}",
  "本次目标",
  "观察方法",
  "学习证据",
  "完成观察题或保存复盘卡后，会进入学习报告和闭环证据。"
];

const operationMarkers = [
  "push|入栈|压栈|压入|进栈",
  "pop|出栈|弹栈|弹出",
  "头插|表头|head",
  "enqueue|入队|进队|队尾",
  "dequeue|出队",
  "冲突|碰撞|链地址|拉链"
];

const failures = [];

for (const file of files) {
  const text = fs.readFileSync(path.join(root, file), "utf8");
  const missing = markers.filter((marker) => !text.includes(marker));
  const missingOperations = operationMarkers.filter((marker) => !text.includes(marker));
  if (missing.length || missingOperations.length) {
    failures.push({ file, missing: [...missing, ...missingOperations] });
  }
}

if (failures.length) {
  for (const failure of failures) {
    console.error(`Missing animation handoff markers in ${failure.file}:`);
    for (const marker of failure.missing) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`animation-handoff-static-ok files=${files.length} markers=${markers.length + operationMarkers.length}`);
