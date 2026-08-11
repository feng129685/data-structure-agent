const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { validateAnimationData } = require("../backend/node/lib/animation-validator");

function main() {
  const valid = validateAnimationData({
    animation: true,
    type: "stack",
    title: "push demo",
    description: "show top changes",
    initial: [1, 2],
    steps: [{ op: "push", label: "push 3", note: "top moves", value: 3 }]
  });
  assert.equal(valid.type, "stack");
  assert.deepEqual(valid.initial, [1, 2]);
  assert.equal(valid.steps[0].op, "push");

  const dsvpAliases = [
    {
      type: "linked_list",
      initial: [1, 2],
      steps: [{ op: "append", label: "append", note: "link node", value: 3 }],
      expectedType: "list"
    },
    {
      type: "sequential_list",
      initial: [1, 2],
      steps: [{ op: "insert", label: "insert", note: "shift values", value: 3, index: 1 }],
      expectedType: "array"
    },
    {
      type: "graph",
      initial: ["A", "B"],
      steps: [{ op: "bfs", label: "bfs", note: "visit node", node: 1 }],
      expectedType: "tree"
    }
  ];
  for (const item of dsvpAliases) {
    const normalized = validateAnimationData({ animation: true, ...item });
    assert.equal(normalized?.type, item.expectedType, `${item.type} should map to a renderer-safe type`);
  }

  const invalidOperation = validateAnimationData({
    animation: true,
    type: "stack",
    initial: [],
    steps: [{ op: "executeScript", value: "alert(1)" }]
  });
  assert.equal(invalidOperation, null, "unknown operations should be rejected");

  const bounded = validateAnimationData({
    animation: true,
    type: "list",
    title: "T".repeat(200),
    description: "D".repeat(600),
    initial: Array.from({ length: 100 }, (_, index) => `<node-${index}>`),
    steps: Array.from({ length: 40 }, (_, index) => ({
      op: "append",
      label: "L".repeat(100),
      note: "N".repeat(500),
      value: `<script-${index}>`
    }))
  });
  assert.equal(bounded.title.length, 60);
  assert.equal(bounded.description.length, 240);
  assert.equal(bounded.initial.length, 64);
  assert.equal(bounded.steps.length, 20);
  assert.equal(bounded.steps[0].label.length, 48);
  assert.equal(bounded.steps[0].note.length, 240);

  const invalidHeap = validateAnimationData({
    animation: true,
    type: "heap",
    initial: [4, "not-a-number", 2],
    steps: [{ op: "insert", value: "also-not-a-number" }]
  });
  assert.equal(invalidHeap, null, "heap animation values must stay numeric");

  const root = path.join(__dirname, "..");
  // The legacy renderer remains available through prototype.html; the Vue
  // entry is validated by the frontend build and component tests.
  for (const filename of ["prototype.html"]) {
    const html = fs.readFileSync(path.join(root, "frontend", filename), "utf8");
    assert.match(html, /linked_list: "list"/);
    assert.match(html, /sequential_list: "array"/);
    assert.match(html, /graph: "tree"/);
    assert.match(html, /bfs: "visit"/);
    assert.match(html, /escapeHtml\(String\(val\)\)/, `${filename} should escape SVG list values`);
    assert.match(html, /escapeHtml\(String\(values\[n\.id - 1\]\)\)/, `${filename} should escape SVG tree values`);
    assert.match(html, /escapeHtml\(String\(data\[i\]\)\)/, `${filename} should escape SVG heap values`);
  }

  console.log("animation-validation-ok schema=4 svg-escaping=3");
}

main();
