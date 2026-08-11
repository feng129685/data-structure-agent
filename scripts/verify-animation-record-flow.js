const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");

assert.match(html, /let animationRecordRequest = null;/);
assert.match(html, /animationRecordRequest\.generation === generation/);
assert.match(html, /if \(animationRecordRequest\?\.promise === promise\) animationRecordRequest = null;/);
assert.match(html, /const handoffChanged = !previousHandoff/);
assert.match(html, /if \(handoffChanged\) invalidateAnimationRecord\(\);/);

const watchStart = html.indexOf("async function saveAnimationWatchEvidence");
const watchEnd = html.indexOf("function sendAnimationWatchToCoach", watchStart);
assert.ok(watchStart >= 0 && watchEnd > watchStart, "watch evidence handler must be present");
const watchSource = html.slice(watchStart, watchEnd);
assert.match(watchSource, /ensureAnimationRecord\(summary\.data, scenario\)/);
assert.match(watchSource, /\/api\/v1\/animations\/\$\{encodeURIComponent\(recordId\)\}\/observations/);
assert.match(watchSource, /if \(!response\.ok \|\| !payload\.recordId\)/);
assert.match(watchSource, /button\.dataset\.saved = "true"/);

const generationStart = html.indexOf("async function sendAnimationRequest");
const generationEnd = html.indexOf("function commitAnimationResult", generationStart);
assert.ok(generationStart >= 0 && generationEnd > generationStart, "animation generation handler must be present");
const generationSource = html.slice(generationStart, generationEnd);
assert.match(generationSource, /animationGenerationErrorMessage\(error\)/);
assert.doesNotMatch(generationSource, /const fallbackAnimation =/);
assert.match(generationSource, /const invalidResponse = new Error/);

console.log("animation-record-flow-static-ok server-owned-observation=required stale-promise=isolated error=visible");
