"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const frontendRoot = path.join(root, "frontend");
const outputRoot = path.join(frontendRoot, "dist");
const manifestPath = path.join(outputRoot, "build-integrity.json");
const indexPath = path.join(outputRoot, "index.html");
// Keep this list synchronized with separately copied runtime assets in Dockerfile.node.
const frontendRuntimeAssets = ["prototype.html"];

assert.ok(fs.existsSync(indexPath), "frontend/dist/index.html is required after a production build");
assert.ok(fs.existsSync(manifestPath), "frontend/dist/build-integrity.json is required after a production build");

const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
assert.equal(manifest.schemaVersion, 1, "build manifest schema must be explicit");
assert.equal(manifest.sourceHash, sourceHash(frontendRoot), "build manifest must match current frontend source");

const index = fs.readFileSync(indexPath, "utf8");
assert.match(index, /<div id="app"><\/div>/, "production bundle must retain the Vue mount point");
assert.doesNotMatch(index, /127\.0\.0\.1:879[12]/, "production bundle must not bake in local backend origins");

const dockerfile = fs.readFileSync(path.join(root, "deployment", "Dockerfile.node"), "utf8");
assert.match(dockerfile, /AS frontend-build/, "Node image must build the frontend in a dedicated stage");
assert.match(dockerfile, /npm run build/, "Node image must fail when the frontend production build fails");
assert.match(dockerfile, /COPY --from=frontend-build[^\n]+dist[^\n]+\.\/frontend/, "Node image must copy only the built frontend output");
assert.doesNotMatch(dockerfile, /COPY[^\n]+frontend \.\/frontend/, "Node image must not copy the complete frontend source tree at runtime");

console.log(`frontend-build-artifact-ok sourceHash=${manifest.sourceHash}`);

function sourceHash(frontendDirectory) {
  const sourceFiles = [
    ...collectFiles(path.join(frontendDirectory, "src")),
    "package.json",
    "package-lock.json",
    "tsconfig.json",
    "vite.config.ts",
    "vite-routing.ts",
    "vitest.config.ts",
    ...frontendRuntimeAssets,
  ].map((file) => path.isAbsolute(file) ? path.relative(frontendDirectory, file) : file)
    .sort();
  const hash = crypto.createHash("sha256");
  for (const relativePath of sourceFiles) {
    hash.update(relativePath.replace(/\\/g, "/"));
    hash.update("\0");
    hash.update(fs.readFileSync(path.join(frontendDirectory, relativePath)));
    hash.update("\0");
  }
  return hash.digest("hex");
}

function collectFiles(directory) {
  const entries = fs.readdirSync(directory, { withFileTypes: true });
  return entries.flatMap((entry) => {
    const fullPath = path.join(directory, entry.name);
    return entry.isDirectory() ? collectFiles(fullPath) : [fullPath];
  });
}
