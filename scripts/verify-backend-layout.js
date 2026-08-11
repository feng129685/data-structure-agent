#!/usr/bin/env node
"use strict";

const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const repositoryRoot = path.resolve(__dirname, "..");
const failures = [];

function read(relativePath) {
  const absolutePath = path.join(repositoryRoot, relativePath);
  if (!fs.existsSync(absolutePath)) {
    failures.push(`${relativePath}: expected file is missing`);
    return "";
  }
  return fs.readFileSync(absolutePath, "utf8");
}

function expectPathExists(relativePath) {
  if (!fs.existsSync(path.join(repositoryRoot, relativePath))) {
    failures.push(`${relativePath}: expected path is missing`);
  }
}

function expectPathAbsent(relativePath) {
  if (fs.existsSync(path.join(repositoryRoot, relativePath))) {
    failures.push(`${relativePath}: stale path still exists`);
  }
}

function expectIncludes(relativePath, value) {
  if (!read(relativePath).includes(value)) {
    failures.push(`${relativePath}: expected ${JSON.stringify(value)}`);
  }
}

function expectExcludes(relativePath, value) {
  if (read(relativePath).includes(value)) {
    failures.push(`${relativePath}: stale ${JSON.stringify(value)}`);
  }
}

function expectEnvironmentValue(relativePath, key, expectedValue) {
  const values = read(relativePath)
    .split(/\r?\n/)
    .filter((line) => line.startsWith(`${key}=`))
    .map((line) => line.slice(key.length + 1));

  if (values.length !== 1 || values[0] !== expectedValue) {
    failures.push(
      `${relativePath}: expected exactly one ${key}=${expectedValue}, found ${JSON.stringify(values)}`
    );
  }
}

function expectTreeExcludes(relativeDirectory, value) {
  const directory = path.join(repositoryRoot, relativeDirectory);
  if (!fs.existsSync(directory)) {
    failures.push(`${relativeDirectory}: expected directory is missing`);
    return;
  }

  const pending = [directory];
  while (pending.length > 0) {
    const current = pending.pop();
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const absolutePath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        pending.push(absolutePath);
        continue;
      }
      if (!entry.isFile()) continue;

      const relativePath = path.relative(repositoryRoot, absolutePath).split(path.sep).join("/");
      if (fs.readFileSync(absolutePath, "utf8").includes(value)) {
        failures.push(`${relativePath}: stale ${JSON.stringify(value)}`);
      }
    }
  }
}

function expectTreeExcludesPattern(relativeDirectory, pattern, label) {
  const directory = path.join(repositoryRoot, relativeDirectory);
  if (!fs.existsSync(directory)) {
    failures.push(`${relativeDirectory}: expected directory is missing`);
    return;
  }

  const pending = [directory];
  while (pending.length > 0) {
    const current = pending.pop();
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const absolutePath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        pending.push(absolutePath);
        continue;
      }
      if (!entry.isFile()) continue;

      const source = fs.readFileSync(absolutePath, "utf8");
      if (pattern.test(source)) {
        const relativePath = path.relative(repositoryRoot, absolutePath).split(path.sep).join("/");
        failures.push(`${relativePath}: stale ${JSON.stringify(label)}`);
      }
    }
  }
}

function expectNodeServerLaunchesUseMigratedCwd() {
  const scriptsDirectory = path.join(repositoryRoot, "scripts");
  const pending = [scriptsDirectory];
  const launchPattern = /spawn(?:Sync)?\(\s*process\.execPath,\s*\[\s*["']server\.js["']\s*\],\s*\{/g;

  while (pending.length > 0) {
    const current = pending.pop();
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const absolutePath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        pending.push(absolutePath);
        continue;
      }
      if (!entry.isFile() || !entry.name.endsWith(".js")) continue;

      const source = fs.readFileSync(absolutePath, "utf8");
      const relativePath = path.relative(repositoryRoot, absolutePath).split(path.sep).join("/");
      for (const match of source.matchAll(launchPattern)) {
        const optionsPrefix = source.slice(match.index, match.index + 256);
        const cwdMatch = optionsPrefix.match(/\bcwd:\s*(nodeRoot|backendRoot)\b/);
        if (!cwdMatch) {
          failures.push(`${relativePath}: server.js launch must set a backend/node working directory`);
          continue;
        }

        const cwdName = cwdMatch[1];
        const expectedDefinition = `const ${cwdName} = path.join(root, "backend", "node");`;
        if (!source.includes(expectedDefinition)) {
          failures.push(`${relativePath}: ${cwdName} must resolve to backend/node`);
        }
      }
    }
  }
}

function expectDeepCheckToAcceptCompatibilityEntry() {
  const result = spawnSync("python", ["scripts/deep_check.py"], {
    cwd: repositoryRoot,
    encoding: "utf8"
  });
  const output = `${result.stdout || ""}${result.stderr || ""}`;
  if (result.error || result.status !== 0) {
    failures.push(`scripts/deep_check.py: command failed: ${result.error || output.trim()}`);
  }
  if (output.includes("called but never defined")) {
    failures.push("scripts/deep_check.py: compatibility entry produced stale undefined-function warnings");
  }
}

expectIncludes("package.json", '"main": "backend/node/server.js"');
expectIncludes("package.json", '"start": "node backend/node/server.js"');
expectIncludes("package.json", '"dev": "node --watch backend/node/server.js"');
expectIncludes(
  "package.json",
  '"test": "node scripts/verify-backend-layout.js && node scripts/verify-core-regression.js"'
);
expectIncludes("scripts/verify-core-regression.js", "scripts/verify-api-contract-fixtures.js");

expectPathExists("backend/node/server.js");
expectPathExists("backend/node/lib");
expectPathExists("backend/spring/pom.xml");
expectPathAbsent("apps/server");
expectPathAbsent("server.js");
expectPathAbsent("lib");

expectExcludes(".github/workflows/ci.yml", "apps/server");
expectIncludes(".github/workflows/ci.yml", "working-directory: backend/spring");
expectTreeExcludes(".github/workflows", "apps/server");

expectIncludes(
  "deployment/Dockerfile.node",
  "COPY --chown=node:node backend/node ./backend/node"
);
expectIncludes("deployment/Dockerfile.node", "CMD [\"node\", \"backend/node/server.js\"]");
expectExcludes("deployment/Dockerfile.node", "backend/node/server.js backend/node/presentation-runtime.js ./");
expectExcludes("deployment/Dockerfile.node", "backend/node/lib ./lib");
expectIncludes("deployment/Dockerfile.node.dockerignore", "!backend/node/server.js");
expectIncludes("deployment/Dockerfile.node.dockerignore", "!backend/node/lib/**");
expectExcludes("deployment/Dockerfile.node.dockerignore", "!server.js");
expectExcludes("deployment/Dockerfile.node.dockerignore", "!lib/**");

expectIncludes("deployment/docker-compose.production.yml", "dockerfile: deployment/Dockerfile.node");
expectIncludes("deployment/docker-compose.production.yml", "context: ../backend/spring");
expectIncludes("deployment/docker-compose.spring.yml", "context: ../backend/spring");
for (const relativePath of [
  "deployment/Dockerfile.node",
  "deployment/Dockerfile.node.dockerignore",
  "deployment/docker-compose.production.yml",
  "deployment/docker-compose.spring.yml"
]) {
  expectExcludes(relativePath, "apps/server");
  expectExcludes(relativePath, "apps\\server");
}

for (const relativePath of [
  "deployment/Caddyfile.production",
  "deployment/Caddyfile.host.production",
  "deployment/Caddyfile.spring.example"
]) {
  expectExcludes(relativePath, "apps/server");
  expectExcludes(relativePath, "apps\\server");
  expectExcludes(relativePath, "server.js");
  expectExcludes(relativePath, "lib/");
}
for (const staleValue of ["apps/server", "apps\\server", "lib/", "lib\\"]) {
  expectTreeExcludes("deployment/scripts", staleValue);
}
expectTreeExcludesPattern(
  "deployment/scripts",
  /(^|[^A-Za-z0-9_\/-])(?:\.\/)?server\.js(?=$|[^A-Za-z0-9_.-])/,
  "root server.js"
);
expectNodeServerLaunchesUseMigratedCwd();

expectExcludes("backend/spring/src/main/resources/application.yml", "../../knowledge/private/textbook");
expectExcludes("backend/spring/src/main/resources/application.yml", "../../course-content-private");
expectIncludes("backend/spring/src/main/resources/application.yml", "${KNOWLEDGE_DIR:../../private/knowledge}");
expectIncludes("backend/spring/src/main/resources/application.yml", "${RESOURCE_DIR:../../private/course-content}");

expectExcludes("deployment/docker-compose.spring.yml", "../knowledge/private/textbook");
expectExcludes("deployment/docker-compose.spring.yml", "../course-content-private");
expectIncludes("deployment/docker-compose.spring.yml", "../private/knowledge:/app/knowledge:ro");
expectIncludes("deployment/docker-compose.spring.yml", "../private/course-content:/app/resources:ro");

expectEnvironmentValue(".env.example", "KNOWLEDGE_DIR", "private/knowledge");
expectExcludes("scripts/import-knowledge-pack.ps1", "knowledge\\private\\textbook");
expectIncludes("scripts/import-knowledge-pack.ps1", "private\\knowledge");

expectExcludes("scripts/deep_check.py", "C:/Users/");
expectIncludes("scripts/deep_check.py", "Path(__file__).resolve().parents[1]");
expectDeepCheckToAcceptCompatibilityEntry();

expectExcludes("contracts/README.md", "apps/server");
expectIncludes("contracts/README.md", "backend/spring");
expectExcludes("knowledge/README.md", "knowledge/private/textbook");
expectIncludes("knowledge/README.md", "private/knowledge");
expectExcludes("backend/spring/README.md", "course-content-private/");
expectIncludes("backend/spring/README.md", "private/course-content/");
expectExcludes("docs/content-import-guide.md", "course-content-private/");
expectIncludes("docs/content-import-guide.md", "private/course-content/");
expectExcludes("docs/production-deployment.md", "knowledge/private/**");
expectExcludes("docs/production-deployment.md", "course-content-private/**");
expectExcludes("README.md", "knowledge/private/");
expectIncludes("README.md", "private/knowledge/");
expectExcludes("README.md", "cp .env.example .env");
expectIncludes("README.md", "cp .env.example backend/node/.env");
expectExcludes("docs/release-source-manifest.md", "knowledge/private/");
expectIncludes("docs/release-source-manifest.md", "course-content/");
expectExcludes("docs/data-model-node-spring-differences.md", "`presentation-materials/` JSON");
expectIncludes("docs/data-model-node-spring-differences.md", "`private/presentation-materials/` JSON");
expectExcludes("docs/data-model-node-spring-differences.md", "Node `pdfs/`");
expectIncludes("docs/data-model-node-spring-differences.md", "Node `private/pdfs`");
expectExcludes("docs/project/SPRINT.md", "C:/Users/Lenovo/");
expectIncludes("docs/project/SPRINT.md", "frontend/prototype.html");

if (failures.length > 0) {
  console.error("Backend layout verification failed:");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exitCode = 1;
} else {
  console.log("Backend layout verification passed.");
}
