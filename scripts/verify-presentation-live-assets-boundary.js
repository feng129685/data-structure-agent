const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const root = path.join(__dirname, "..");
const sourceVerifier = path.join(root, "scripts", "verify-presentation-live-assets.js");
const externalResourcesRequired = "PRESENTATION_LIVE_ASSETS_EXTERNAL_RESOURCES_REQUIRED";

function runVerifier(scriptPath, environment) {
  return spawnSync(process.execPath, [scriptPath], {
    cwd: path.dirname(path.dirname(scriptPath)),
    env: { ...process.env, ...environment },
    encoding: "utf8"
  });
}

function main() {
  const cleanReleaseRoot = fs.mkdtempSync(path.join(os.tmpdir(), "structify-presentation-boundary-"));
  const cleanScriptsDir = path.join(cleanReleaseRoot, "scripts");
  const cleanVerifier = path.join(cleanScriptsDir, "verify-presentation-live-assets.js");

  try {
    fs.mkdirSync(cleanScriptsDir, { recursive: true });
    fs.copyFileSync(sourceVerifier, cleanVerifier);

    const optionalResources = runVerifier(cleanVerifier, {
      STRUCTIFY_PRIVATE_ROOT: path.join(cleanReleaseRoot, "private"),
      STRUCTIFY_REQUIRE_PRIVATE_RESOURCES: "false"
    });
    assert.equal(optionalResources.status, 0, optionalResources.stderr || "clean release validation should succeed");
    assert.match(optionalResources.stdout, new RegExp(`^${externalResourcesRequired}$`, "m"));
    assert.doesNotMatch(optionalResources.stderr, /ENOENT/);

    const requiredResources = runVerifier(cleanVerifier, {
      STRUCTIFY_PRIVATE_ROOT: path.join(cleanReleaseRoot, "private"),
      STRUCTIFY_REQUIRE_PRIVATE_RESOURCES: "true"
    });
    assert.notEqual(requiredResources.status, 0, "strict validation must fail without the external resource bundle");
    assert.match(requiredResources.stderr, new RegExp(externalResourcesRequired));
    assert.doesNotMatch(requiredResources.stderr, /ENOENT/);

    console.log("presentation-live-assets-boundary-ok clean-release=external-resources-required strict=blocked");
  } finally {
    fs.rmSync(cleanReleaseRoot, { recursive: true, force: true });
  }
}

main();
