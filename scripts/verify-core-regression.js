const fs = require("fs");
const path = require("path");
const vm = require("vm");
const { spawnSync } = require("child_process");

const root = path.join(__dirname, "..");

const inlineScriptFiles = ["frontend/index.html", "frontend/prototype.html"];
const nodeCheckFiles = [
  "backend/node/server.js",
  "backend/node/presentation-runtime.js",
  "backend/node/lib/knowledge-retriever.js",
  "backend/node/lib/animation-validator.js",
  "backend/node/lib/dsvp-adapter.js",
  "scripts/verify-core-regression.js",
  "scripts/verify-knowledge-retrieval.js",
  "scripts/verify-knowledge-api.js",
  "scripts/verify-knowledge-chat-context.js",
  "scripts/verify-security-hardening.js",
  "scripts/verify-model-stream-errors.js",
  "scripts/verify-animation-validation.js",
  "scripts/verify-animation-record-flow.js",
  "scripts/verify-dsvp-adapter.js",
  "scripts/verify-dsvp-api.js",
  "scripts/verify-presentation-runtime.js",
  "scripts/verify-presentation-ui-static.js",
  "scripts/verify-presentation-live-assets.js",
  "scripts/verify-presentation-live-assets-boundary.js",
  "scripts/verify-four-core-navigation-static.js",
  "scripts/verify-four-core-ui-static.js",
  "scripts/verify-model-availability-ui-static.js",
  "scripts/verify-presentation-api.js",
  "scripts/verify-frontend-layout.js",
  "scripts/check-ppt-offline.js",
  "scripts/verify-private-resource-bundle.js",
  "scripts/verify-case-demo-static.js",
  "scripts/verify-compiler-response-behavior.js",
  "scripts/execute-security-check.js",
  "scripts/verify-production-config.js",
  "scripts/verify-api-contract-fixtures.js",
  "scripts/verify-node-production-debug-knowledge.js",
  "scripts/verify-teacher-assignment-ownership.js"
];
const verifierScripts = [
  "scripts/verify-knowledge-retrieval.js",
  "scripts/verify-knowledge-api.js",
  "scripts/verify-knowledge-chat-context.js",
  "scripts/verify-security-hardening.js",
  "scripts/verify-model-stream-errors.js",
  "scripts/verify-animation-validation.js",
  "scripts/verify-animation-record-flow.js",
  "scripts/verify-case-demo-static.js",
  "scripts/verify-compiler-response-behavior.js",
  "scripts/execute-security-check.js",
  "scripts/verify-production-config.js",
  "scripts/verify-api-contract-fixtures.js",
  "scripts/verify-node-production-debug-knowledge.js",
  "scripts/verify-teacher-assignment-ownership.js",
  "scripts/verify-dsvp-adapter.js",
  "scripts/verify-dsvp-api.js",
  "scripts/verify-presentation-runtime.js",
  "scripts/verify-presentation-ui-static.js",
  "scripts/verify-presentation-live-assets.js",
  "scripts/verify-presentation-live-assets-boundary.js",
  "scripts/verify-four-core-navigation-static.js",
  "scripts/verify-four-core-ui-static.js",
  "scripts/verify-model-availability-ui-static.js",
  "scripts/check-ppt-offline.js",
  "scripts/verify-private-resource-bundle.js",
  "scripts/verify-presentation-api.js",
  "scripts/verify-frontend-layout.js",
  "scripts/verify-learning-evidence-checklist-static.js",
  "scripts/verify-learning-snapshot-static.js",
  "scripts/verify-evidence-aware-orchestrator-static.js",
  "scripts/verify-study-handoff-static.js",
  "scripts/verify-learning-handoff-context-static.js",
  "scripts/verify-chat-learning-trace-static.js",
  "scripts/verify-orchestrator-quiet-ui-static.js",
  "scripts/verify-coach-today-step-static.js",
  "scripts/verify-home-continuity-static.js",
  "scripts/verify-guest-nav-static.js",
  "scripts/verify-animation-observation-static.js",
  "scripts/verify-animation-watch-evidence-static.js",
  "scripts/verify-animation-handoff-static.js",
  "scripts/verify-animation-loop-static.js",
  "scripts/verify-compiler-observation-static.js",
  "scripts/verify-compiler-evidence-loop-static.js",
  "scripts/verify-classroom-harvest-static.js",
  "scripts/verify-classroom-review-static.js",
  "scripts/verify-student-teacher-task-static.js",
  "scripts/verify-student-teacher-task-evidence-status-static.js",
  "scripts/verify-teacher-evidence-focus-static.js",
  "scripts/verify-teacher-task-step-evidence-static.js",
  "scripts/verify-teacher-assignment-step-evidence-static.js",
  "scripts/verify-teacher-step-evidence-coverage-static.js"
];

const hasVueCanonicalEntry = fs.existsSync(path.join(root, "frontend", "src", "main.ts"));
const legacyFrontendVerifierScripts = new Set(
  verifierScripts.filter((script) => script.endsWith("-static.js"))
);
legacyFrontendVerifierScripts.add("scripts/verify-animation-record-flow.js");
const activeVerifierScripts = hasVueCanonicalEntry
  ? verifierScripts.filter((script) => !legacyFrontendVerifierScripts.has(script))
  : verifierScripts;

const checks = [];

function rel(filePath) {
  return filePath.split(path.sep).join("/");
}

function fail(message) {
  console.error(`\ncore-regression-failed: ${message}`);
  process.exit(1);
}

function pass(label, detail) {
  checks.push({ label, detail });
  console.log(`ok ${checks.length} - ${label}${detail ? ` (${detail})` : ""}`);
}

function assertFileExists(relativePath) {
  const absolutePath = path.join(root, relativePath);
  if (!fs.existsSync(absolutePath)) fail(`missing file ${relativePath}`);
  return absolutePath;
}

function checkInlineScripts(relativePath) {
  const absolutePath = assertFileExists(relativePath);
  const html = fs.readFileSync(absolutePath, "utf8");
  const scriptRegex = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
  let inlineCount = 0;
  let match;

  while ((match = scriptRegex.exec(html))) {
    const attrs = match[1] || "";
    if (/\bsrc\s*=/.test(attrs)) continue;

    inlineCount += 1;
    const source = match[2] || "";
    try {
      new vm.Script(source, {
        filename: `${rel(relativePath)}#inline-script-${inlineCount}`
      });
    } catch (error) {
      const line = typeof error.lineNumber === "number" ? `:${error.lineNumber}` : "";
      fail(`${relativePath} inline script ${inlineCount}${line} has invalid syntax: ${error.message}`);
    }
  }

  if (!inlineCount) fail(`${relativePath} has no inline scripts to verify`);
  pass(`inline-script-syntax ${relativePath}`, `${inlineCount} script${inlineCount === 1 ? "" : "s"}`);
}

function runNode(args, label) {
  const result = spawnSync(process.execPath, args, {
    cwd: root,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  });

  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);

  if (result.status !== 0) {
    fail(`${label} exited with code ${result.status}`);
  }

  pass(label);
}

for (const file of inlineScriptFiles) {
  checkInlineScripts(file);
}

for (const file of nodeCheckFiles) {
  assertFileExists(file);
  runNode(["--check", file], `node-check ${file}`);
}

for (const script of activeVerifierScripts) {
  assertFileExists(script);
  runNode([script], rel(script));
}

if (hasVueCanonicalEntry) {
  console.log(`legacy-frontend-static-verifiers-skipped count=${verifierScripts.length - activeVerifierScripts.length}`);
}

console.log(`\ncore-regression-ok checks=${checks.length}`);
