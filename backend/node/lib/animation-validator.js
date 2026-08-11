const ALLOWED_OPERATIONS = Object.freeze({
  stack: new Set(["push", "pop", "peek"]),
  list: new Set(["append", "insert", "delete", "deleteValue", "find"]),
  tree: new Set(["visit", "highlight"]),
  queue: new Set(["enqueue", "dequeue", "peek"]),
  heap: new Set(["insert", "extract", "peek"]),
  hash: new Set(["put", "get", "delete"]),
  array: new Set(["set", "insert", "delete", "swap", "get"])
});

const TYPE_ALIASES = Object.freeze({
  linked_list: "list",
  sequential_list: "array",
  graph: "tree"
});

const OP_ALIASES = Object.freeze({
  top: "peek",
  front: "peek",
  extractmin: "extract",
  removeroot: "extract",
  deletevalue: "deleteValue",
  merge: "insert",
  bfs: "visit",
  dfs: "highlight"
});

function normalizeType(value) {
  const raw = String(value || "");
  return TYPE_ALIASES[raw] || raw;
}

function normalizeOperation(value) {
  const raw = String(value || "").toLowerCase().replace(/[\s_-]+/g, "");
  return OP_ALIASES[raw] || raw;
}

function normalizeScalar(value, maxLength = 48) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") return value.slice(0, maxLength);
  if (typeof value === "boolean") return String(value);
  return undefined;
}

function normalizeInteger(value, max = 1024) {
  const number = Number(value);
  if (!Number.isInteger(number) || number < 0 || number > max) return undefined;
  return number;
}

function normalizeInitial(type, value) {
  const initial = Array.isArray(value) ? value : [];
  if (type === "hash") {
    return (initial.length ? initial : Array.from({ length: 8 }, () => []))
      .slice(0, 16)
      .map((bucket) => Array.isArray(bucket)
        ? bucket.slice(0, 8).map((entry) => ({
            key: String(entry?.key ?? "").slice(0, 48),
            val: String(entry?.val ?? "").slice(0, 48)
          }))
        : []);
  }
  if (type === "heap") {
    if (initial.some((item) => typeof item !== "number" || !Number.isFinite(item))) return null;
    return initial.slice(0, 64);
  }
  return initial
    .slice(0, 64)
    .map((item) => normalizeScalar(item))
    .filter((item) => item !== undefined);
}

function normalizeStep(type, step, index) {
  if (!step || typeof step !== "object") return null;
  const op = normalizeOperation(step.op || step.action || step.operation);
  if (!ALLOWED_OPERATIONS[type].has(op)) return null;

  const normalized = {
    op,
    label: String(step.label || `Step ${index + 1}`).slice(0, 48),
    note: String(step.note || step.description || step.summary || "").slice(0, 240)
  };

  if (type === "heap" && step.value !== undefined) {
    if (typeof step.value !== "number" || !Number.isFinite(step.value)) return null;
    normalized.value = step.value;
  } else {
    const value = normalizeScalar(step.value);
    if (value !== undefined) normalized.value = value;
  }

  for (const field of ["index", "node", "i", "j"]) {
    const integer = normalizeInteger(step[field], field === "node" ? 64 : 1024);
    if (integer !== undefined) normalized[field] = integer;
  }
  if (step.key !== undefined) normalized.key = String(step.key).slice(0, 48);
  if (step.val !== undefined) normalized.val = String(step.val).slice(0, 48);

  const requiresValue = (
    (type === "stack" && op === "push")
    || (type === "list" && ["append", "insert", "deleteValue", "find"].includes(op))
    || (type === "queue" && op === "enqueue")
    || (type === "heap" && op === "insert")
    || (type === "array" && ["set", "insert"].includes(op))
  );
  if (requiresValue && normalized.value === undefined) return null;
  if (type === "hash" && ["put", "get", "delete"].includes(op) && !normalized.key) return null;
  if (type === "hash" && op === "put" && normalized.val === undefined) return null;
  if (type === "array" && op === "swap" && (normalized.i === undefined || normalized.j === undefined)) return null;

  return normalized;
}

function validateAnimationData(data) {
  if (!data || typeof data !== "object" || data.animation !== true) return null;
  const type = normalizeType(data.type);
  if (!Object.hasOwn(ALLOWED_OPERATIONS, type)) return null;

  const initial = normalizeInitial(type, data.initial);
  if (!initial) return null;
  const steps = (Array.isArray(data.steps) ? data.steps : [])
    .slice(0, 20)
    .map((step, index) => normalizeStep(type, step, index))
    .filter(Boolean);
  if (!steps.length) return null;

  return {
    animation: true,
    type,
    title: String(data.title || "Data structure animation").slice(0, 60),
    description: String(data.description || "").slice(0, 240),
    initial,
    steps
  };
}

module.exports = {
  ALLOWED_OPERATIONS,
  TYPE_ALIASES,
  validateAnimationData
};
