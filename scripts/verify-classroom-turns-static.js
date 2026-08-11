const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "frontend", "prototype.html");
const html = fs.readFileSync(htmlPath, "utf8");

const markers = [
  "function normalizeClassroomInteraction",
  "function renderClassroomInteractionCard",
  "function submitClassroomAnswer",
  "function buildClassroomFollowupPrompt",
  "async function requestClassroomFollowup",
  "function generateClassroomAnswerFallback",
  "recordReviewNote({",
  "source: \"\u8bfe\u5802\u68c0\u67e5\u70b9\"",
  "action: \"classroom-checkpoint\"",
  "question: prompt.question || \"\"",
  "targetConcept: prompt.targetConcept || \"\"",
  "expected: prompt.expected || \"\"",
  "data-classroom-choice",
  "data-classroom-free-submit",
  "classroom-interaction-card",
  "pendingPrompt",
  "turnHistory",
  "\u9664\u975e\u95ee\u9898\u5b8c\u5168\u662f\u8d44\u6599\u6574\u7406\uff0c\u5426\u5219\u4f18\u5148\u7ed9 interaction",
  "\u53ea\u8ba9 1 \u5230 2 \u4e2a\u6700\u76f8\u5173\u89d2\u8272\u56de\u5e94",
  "\"phase\"",
  "\"interaction\""
];

const missing = markers.filter((marker) => !html.includes(marker));

if (missing.length) {
  console.error("Missing classroom turn markers:");
  for (const marker of missing) console.error(`- ${marker}`);
  process.exit(1);
}

console.log(`classroom-turns-static-ok markers=${markers.length}`);
