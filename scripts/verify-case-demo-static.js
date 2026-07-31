const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "..");
const files = ["index.html", "prototype.html"];
const requiredMarkers = [
  'id="caseDemoGuide"',
  'data-case-action="question"',
  'data-case-action="materials"',
  'data-case-action="animation"',
  'data-case-action="compiler"',
  'data-case-action="reflection"',
  "data-case-reset",
  "const CASE_DEMO_ID",
  "const CASE_DEMO_STORAGE_KEY",
  "function isCaseDemoMode()",
  'document.body.classList.add("case-demo")',
  "function initCaseDemoGuide()",
  "function renderKnowledgeSources",
  "sourceLabel",
  "reviewStatus",
  "body.case-demo .coach-today-step",
  ".case-demo .learning-orchestrator-card",
  "待教师终审"
];

for (const file of files) {
  const html = fs.readFileSync(path.join(root, file), "utf8");
  for (const marker of requiredMarkers) {
    assert.ok(html.includes(marker), `${file} is missing case-demo marker: ${marker}`);
  }
  assert.match(
    html,
    /if \(isCaseDemoMode\(\)\) \{[\s\S]{0,500}?hideLogin\(\)/,
    `${file} must bypass only the login overlay in case mode`
  );
  assert.match(
    html,
    /function canUseGuestChat\(\) \{[\s\S]{0,180}?isCaseDemoMode\(\)/,
    `${file} must isolate the case-mode chat quota behavior`
  );
  assert.match(
    html,
    /action === "materials"[\s\S]{0,180}?markLearningStep\("read", "stack"\)/,
    `${file} must synchronize the case materials step with learning progress`
  );
  assert.match(
    html,
    /action === "animation"[\s\S]{0,260}?markLearningStep\("animate", "stack"\)/,
    `${file} must synchronize the case animation step with learning progress`
  );
  assert.match(
    html,
    /action === "reflection"[\s\S]{0,180}?markLearningStep\("quiz", "stack"\)/,
    `${file} must synchronize the case reflection step with learning progress`
  );
  assert.match(
    html,
    /async function sendAnimationRequest\(scenarioOverride, options = \{\}\) \{[\s\S]{0,900}?if \(isCaseDemoMode\(\)\) \{[\s\S]{0,500}?commitAnimationResult\([\s\S]{0,220}?markCaseDemoStep\("animation"\);/,
    `${file} must open the deterministic stack animation without requiring login in case mode`
  );
  assert.match(
    html,
    /if \(isCaseDemoMode\(\)\) \{[\s\S]{0,800}?return;\s*\}\s*if \(!requireAuth\("AI 动画生成"\)\) return;/,
    `${file} must preserve login protection outside case mode`
  );
  assert.match(
    html,
    /if \(resetButton\) \{[\s\S]{0,500}?window\.history\.replaceState\(null, "", nextUrl\.toString\(\)\);\s*window\.location\.reload\(\);/,
    `${file} must fully reload after clearing case progress`
  );
  assert.match(
    html,
    /@media \(max-width: 560px\) \{[\s\S]{0,800}?\.case-demo-steps \{[\s\S]{0,180}?grid-template-columns: repeat\(2, minmax\(0, 1fr\)\);/,
    `${file} must show every case step without horizontal clipping on phones`
  );
}

const index = fs.readFileSync(path.join(root, "index.html"), "utf8");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
assert.equal(index, prototype, "index.html and prototype.html must remain synchronized");

console.log(`case-demo-static-ok files=${files.length} markers=${requiredMarkers.length}`);
