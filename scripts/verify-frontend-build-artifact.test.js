"use strict";

const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const root = path.resolve(__dirname, "..");

test("rejects a stale separately deployed prototype artifact", () => {
  const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), "ds-frontend-artifact-"));
  try {
    copyFixture(fixtureRoot);
    const baseline = runVerifier(fixtureRoot);
    assert.equal(baseline.status, 0, baseline.stdout + baseline.stderr);

    fs.appendFileSync(path.join(fixtureRoot, "frontend", "prototype.html"), "\n<!-- stale-runtime-prototype -->\n", "utf8");

    const result = runVerifier(fixtureRoot);

    assert.notEqual(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stderr, /build manifest must match current frontend source/);
  } finally {
    fs.rmSync(fixtureRoot, { recursive: true, force: true });
  }
});

function copyFixture(fixtureRoot) {
  fs.mkdirSync(path.join(fixtureRoot, "frontend"), { recursive: true });
  copyTree(path.join(root, "frontend", "src"), path.join(fixtureRoot, "frontend", "src"));
  for (const relativePath of [
    "frontend/package.json",
    "frontend/package-lock.json",
    "frontend/tsconfig.json",
    "frontend/vite.config.ts",
    "frontend/vite-routing.ts",
    "frontend/vitest.config.ts",
    "frontend/prototype.html",
    "frontend/dist/index.html",
    "frontend/dist/build-integrity.json",
    "deployment/Dockerfile.node",
    "scripts/verify-frontend-build-artifact.js",
  ]) {
    const destination = path.join(fixtureRoot, relativePath);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(path.join(root, relativePath), destination);
  }
}

function copyTree(source, destination) {
  fs.mkdirSync(destination, { recursive: true });
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    const sourcePath = path.join(source, entry.name);
    const destinationPath = path.join(destination, entry.name);
    if (entry.isDirectory()) copyTree(sourcePath, destinationPath);
    else fs.copyFileSync(sourcePath, destinationPath);
  }
}

function runVerifier(fixtureRoot) {
  return spawnSync(process.execPath, [path.join(fixtureRoot, "scripts", "verify-frontend-build-artifact.js")], {
    cwd: fixtureRoot,
    encoding: "utf8",
  });
}
