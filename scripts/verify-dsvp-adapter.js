const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const {
  DSVP_VERSION,
  DsvpValidationError,
  adaptDsvp
} = require("../backend/node/lib/dsvp-adapter");
const { validateAnimationData } = require("../backend/node/lib/animation-validator");

const requests = [
  ["stack", "push", { data: [1], value: 2 }, "stack"],
  ["queue", "dequeue", { data: [1, 2] }, "queue"],
  ["sequential_list", "merge", { data: [[1, 3], [2, 4]] }, "array"],
  ["linked_list", "append", { data: [1], value: 2 }, "list"],
  ["tree", "visit", { data: [8, 4, 12], node: 2 }, "tree"],
  ["graph", "bfs", { data: ["A", "B", "C"], node: 1 }, "tree"],
  ["heap", "insert", { data: [2, 5], value: 1 }, "heap"],
  ["hash", "put", { data: [[], []], key: "a", val: "1" }, "hash"],
  ["array", "swap", { data: [1, 2], i: 0, j: 1 }, "array"]
];

for (const [structure, operation, params, rendererType] of requests) {
  const result = adaptDsvp({
    version: DSVP_VERSION,
    structure,
    operation,
    params,
    initial_state: { data: params.data, metadata: { capacity: 10 } },
    source_ref: `test/${structure}/${operation}`
  });
  assert.equal(result.protocol, "dsvp/1.0");
  assert.equal(result.request.structure, structure);
  assert.equal(result.request.operation, operation);
  assert.equal(result.animationData.type, rendererType);
  assert.equal(result.animationData.steps.length, structure === "sequential_list" && operation === "merge" ? 2 : 1);
  const rendererSafe = validateAnimationData(result.animationData);
  assert.ok(rendererSafe, `${structure}/${operation} must survive the shared animation sanitizer`);
  assert.equal(rendererSafe.type, rendererType);
  if (structure === "sequential_list" && operation === "merge") {
    assert.deepEqual(rendererSafe.initial, [1, 3]);
    assert.deepEqual(rendererSafe.steps.map((item) => item.value), [2, 4]);
    assert.deepEqual(rendererSafe.steps.map((item) => item.index), [2, 3]);
  }
  assert.match(result.trace.trace_id, /^dsvp_[a-f0-9]{20}$/);
}

const exampleDir = path.join(__dirname, "..", "contracts", "examples", "dsvp");
const exampleFiles = fs.readdirSync(exampleDir).filter((name) => name.endsWith(".json"));
assert.equal(exampleFiles.length, requests.length, "every supported DSVP structure must have one example");
for (const file of exampleFiles) {
  const example = JSON.parse(fs.readFileSync(path.join(exampleDir, file), "utf8"));
  const adapted = adaptDsvp(example);
  assert.equal(adapted.protocol, "dsvp/1.0");
  assert.equal(adapted.animationData.animation, true);
  assert.ok(validateAnimationData(adapted.animationData), `${file} must survive the shared animation sanitizer`);
}

const legacyEnvelope = adaptDsvp({
  protocol: "dsvp/1",
  request: {
    version: "1.0",
    structure: "stack",
    operation: "push",
    params: { value: 7 },
    initial_state: { data: [2, 5] }
  }
});
assert.equal(legacyEnvelope.protocol, "dsvp/1.0");
assert.equal(legacyEnvelope.request.version, "1.0");

const contextual = adaptDsvp({
  version: "1.0",
  structure: "stack",
  operation: "peek",
  params: {},
  initial_state: { data: [1] },
  source_ref: "frontend/03-stack-queue",
  context: {
    chapter_id: "03-stack-queue",
    source_type: "API",
    source_ref: "frontend/03-stack-queue"
  }
});
assert.deepEqual(contextual.request.context, {
  chapter_id: "03-stack-queue",
  source_type: "API",
  source_ref: "frontend/03-stack-queue"
});

assert.throws(
  () => adaptDsvp({
    protocol: "dsvp/9",
    request: {
      version: "1.0",
      structure: "stack",
      operation: "peek",
      params: {},
      initial_state: { data: [] }
    }
  }),
  (error) => error instanceof DsvpValidationError && error.code === "UNSUPPORTED_PROTOCOL"
);

assert.throws(
  () => adaptDsvp({ version: "9.0", structure: "stack", operation: "push", params: { value: 1 }, initial_state: { data: [] } }),
  (error) => error instanceof DsvpValidationError && error.code === "UNSUPPORTED_VERSION"
);
assert.throws(
  () => adaptDsvp({ version: DSVP_VERSION, structure: "stack", operation: "execute", params: {}, initial_state: { data: [] } }),
  (error) => error instanceof DsvpValidationError && error.code === "UNSUPPORTED_OPERATION"
);
assert.throws(
  () => adaptDsvp({ version: DSVP_VERSION, structure: "hash", operation: "put", params: { key: "x" }, initial_state: { data: [[]] } }),
  (error) => error instanceof DsvpValidationError && error.code === "MISSING_VALUE"
);

console.log(`dsvp-adapter-ok structures=${requests.length} protocol=dsvp/${DSVP_VERSION}`);
