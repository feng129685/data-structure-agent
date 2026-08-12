#!/usr/bin/env node
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const openapi = fs.readFileSync(path.join(root, "contracts", "openapi-v1.yaml"), "utf8");
const fixturePath = path.join(root, "fixtures", "http", "spring-v1-admin-and-ai.json");
const fixture = JSON.parse(fs.readFileSync(fixturePath, "utf8"));

assertNoIllegalBacktickScalars(openapi);
const documentedOperations = extractDocumentedOperations(openapi);

const documentedPaths = [
  "/chat",
  "/chat/stream",
  "/code/runs",
  "/code/run",
  "/ai/readiness",
  "/admin/capabilities",
  "/admin/users",
  "/admin/users/{id}",
  "/admin/users/{id}/status",
  "/admin/users/{id}/roles",
  "/admin/audit-events",
  "/admin/background-tasks",
  "/admin/background-tasks/{id}",
  "/admin/background-tasks/recover-timeouts",
  "/admin/background-tasks/{id}/retry",
  "/admin/background-tasks/{id}/cancel",
  "/admin/reviews",
  "/admin/reviews/{type}/{id}",
  "/admin/reviews/{type}/{id}/status",
  "/admin/reviews/{type}/{id}/history",
  "/admin/model-config",
  "/admin/model-config/test"
];

assert.match(openapi, /^openapi: 3\.0\.3/m);
assert.match(openapi, /- name: Admin/m);
assert.match(openapi, /- name: AI/m);
assert.match(openapi, /  \/chat:\n    post:[\s\S]*?security: \[\{cookieAuth: \[\]\}, \{bearerAuth: \[\]\}\]/m);
assert.match(openapi, /  \/chat\/stream:\n    post:[\s\S]*?sources, delta, done, and error[\s\S]*?text\/event-stream/m);
assert.match(openapi, /  \/code\/run:\n    post:[\s\S]*?deprecated: true/m, "code/run must remain an explicit deprecated alias");
assert.match(
  openapi,
  /Only `VERIFIED` knowledge chunks[\s\S]*?Merely `PUBLISHED`, legacy-unverified, draft, and excluded/m,
  "knowledge retrieval must retain the verified source-chain gate"
);
assert.match(
  openapi,
  /ChatSource:\n[\s\S]*?required: \[[^\]]*evidenceHash[^\]]*\][\s\S]*?evidenceHash:/m,
  "chat citations must retain a required evidence hash"
);

for (const endpoint of documentedPaths) {
  assert.match(
    openapi,
    new RegExp(`^  ${endpoint.replace(/[{}]/g, "\\$&")}:`, "m"),
    `OpenAPI is missing ${endpoint}`
  );
}

assert.equal(fixture.contract, "openapi-v1");
assert.equal(fixture.server, "/api/v1");
assert.ok(Array.isArray(fixture.cases) && fixture.cases.length >= 5, "expected representative HTTP cases");

const fixturePaths = new Set();
const fixtureOperations = new Set();
for (const example of fixture.cases) {
  assert.equal(typeof example.method, "string", "fixture method is required");
  assert.equal(typeof example.path, "string", "fixture path is required");
  assert.ok(documentedPaths.includes(example.path), `fixture path is undocumented: ${example.path}`);
  const operation = `${example.method} ${example.path}`;
  assert.ok(documentedOperations.has(operation), `fixture operation is undocumented: ${operation}`);
  fixturePaths.add(example.path);
  fixtureOperations.add(operation);
  assert.equal(typeof example.response, "object", "fixture response is required");
  assertNoSensitiveResponseData(example.response, example.path);
}

for (const endpoint of documentedPaths) {
  assert.ok(fixturePaths.has(endpoint), `fixture coverage is missing ${endpoint}`);
}

for (const operation of documentedOperations) {
  const endpoint = operation.slice(operation.indexOf(" ") + 1);
  if (documentedPaths.includes(endpoint)) {
    assert.ok(fixtureOperations.has(operation), `fixture coverage is missing ${operation}`);
  }
}

const auditFixture = requireFixtureCase("GET", "/admin/audit-events");
assert.ok(
  Array.isArray(auditFixture.response.items) && auditFixture.response.items.length > 0,
  "audit fixture must contain an event"
);
assert.equal(
  auditFixture.response.items[0].action,
  "USER_STATUS_CHANGED",
  "audit fixture action must match the runtime user-status action"
);

const taskDetailFixture = requireFixtureCase("GET", "/admin/background-tasks/{id}");
const retryFixture = requireFixtureCase("POST", "/admin/background-tasks/{id}/retry");
assert.equal(retryFixture.response.requestId, "req-fixture-23", "retry must preserve the task requestId");
assert.equal(
  retryFixture.response.requestId,
  taskDetailFixture.response.requestId,
  "retry must retain the requestId recorded when the task was created"
);

const taskListFixture = requireFixtureCase("GET", "/admin/background-tasks");
const cancelFixture = requireFixtureCase("POST", "/admin/background-tasks/{id}/cancel");
const pendingTask = taskListFixture.response.items.find((item) => item.id === cancelFixture.response.id);
assert.ok(pendingTask, "cancel fixture must reference the listed pending task");
assert.equal(cancelFixture.response.requestId, "req-fixture-24", "cancel must preserve the task requestId");
assert.equal(
  cancelFixture.response.requestId,
  pendingTask.requestId,
  "cancel must retain the requestId recorded when the task was created"
);

console.log(`api-contract-fixtures-ok cases=${fixture.cases.length} paths=${documentedPaths.length}`);

function extractDocumentedOperations(source) {
  const operations = new Set();
  let inPaths = false;
  let currentPath = null;

  for (const line of source.split(/\r?\n/)) {
    if (line === "paths:") {
      inPaths = true;
      continue;
    }
    if (!inPaths) {
      continue;
    }
    if (/^\S/.test(line)) {
      break;
    }

    const pathMatch = line.match(/^  (\/[^:]+):\s*(?:#.*)?$/);
    if (pathMatch) {
      currentPath = pathMatch[1];
      continue;
    }
    const methodMatch = currentPath && line.match(/^    (get|put|post|delete|options|head|patch|trace):\s*(?:#.*)?$/);
    if (methodMatch) {
      operations.add(`${methodMatch[1].toUpperCase()} ${currentPath}`);
    }
  }

  assert.ok(operations.size > 0, "OpenAPI paths contain no HTTP operations");
  return operations;
}

function assertNoIllegalBacktickScalars(source) {
  let blockScalarIndent = null;

  source.split(/\r?\n/).forEach((line, index) => {
    const trimmed = line.trimStart();
    const indent = line.length - trimmed.length;

    if (blockScalarIndent !== null) {
      if (trimmed.length === 0 || indent > blockScalarIndent) {
        return;
      }
      blockScalarIndent = null;
    }

    if (/(?:^|:\s+|-\s+)[>|](?:[1-9][+-]?|[+-][1-9]?)?\s*(?:#.*)?$/.test(line)) {
      blockScalarIndent = indent;
      return;
    }

    assert.ok(
      !hasUnquotedBacktickScalarStart(line),
      `OpenAPI contains an illegal backtick-prefixed YAML scalar at line ${index + 1}`
    );
  });
}

function hasUnquotedBacktickScalarStart(line) {
  let singleQuoted = false;
  let doubleQuoted = false;

  for (let index = 0; index < line.length; index += 1) {
    const character = line[index];
    if (doubleQuoted && character === "\\") {
      index += 1;
      continue;
    }
    if (!doubleQuoted && character === "'") {
      if (singleQuoted && line[index + 1] === "'") {
        index += 1;
      } else {
        singleQuoted = !singleQuoted;
      }
      continue;
    }
    if (!singleQuoted && character === '"') {
      doubleQuoted = !doubleQuoted;
      continue;
    }
    if (singleQuoted || doubleQuoted || character !== "`") {
      continue;
    }

    let previousIndex = index - 1;
    while (previousIndex >= 0 && /\s/.test(line[previousIndex])) {
      previousIndex -= 1;
    }
    if (previousIndex < 0) {
      return true;
    }

    const previous = line[previousIndex];
    if ([":", "[", "{", ",", "?"].includes(previous)) {
      return true;
    }
    if (previous === "-") {
      return previousIndex === 0 || /\s|\[|\{|,/.test(line[previousIndex - 1]);
    }
  }

  return false;
}

function requireFixtureCase(method, endpoint) {
  const example = fixture.cases.find((candidate) => candidate.method === method && candidate.path === endpoint);
  assert.ok(example, `fixture is missing ${method} ${endpoint}`);
  return example;
}

function assertNoSensitiveResponseData(value, location) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => assertNoSensitiveResponseData(item, `${location}[${index}]`));
    return;
  }
  if (value && typeof value === "object") {
    for (const [key, nested] of Object.entries(value)) {
      assert.ok(
        !["apikey", "api_key", "token", "password", "secret", "credential"].includes(key.toLowerCase()),
        `fixture response exposes sensitive field ${location}.${key}`
      );
      assertNoSensitiveResponseData(nested, `${location}.${key}`);
    }
    return;
  }
  if (typeof value === "string") {
    assert.doesNotMatch(value, /bearer\s+|api[-_ ]?key\s*[:=]|password\s*[:=]|secret\s*[:=]/i);
  }
}
