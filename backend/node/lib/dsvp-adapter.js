const crypto = require("node:crypto");

const DSVP_VERSION = "1.0";
const DSVP_PROTOCOL = `dsvp/${DSVP_VERSION}`;
const SUPPORTED_PROTOCOLS = new Set(["dsvp/1", DSVP_PROTOCOL]);

const SUPPORTED_OPERATIONS = Object.freeze({
  stack: new Set(["push", "pop", "peek"]),
  queue: new Set(["enqueue", "dequeue", "peek"]),
  sequential_list: new Set(["insert", "delete", "merge"]),
  linked_list: new Set(["append", "insert", "delete", "find"]),
  tree: new Set(["visit", "highlight"]),
  graph: new Set(["bfs", "dfs", "visit"]),
  heap: new Set(["insert", "extract", "peek"]),
  hash: new Set(["put", "get", "delete"]),
  array: new Set(["set", "insert", "delete", "swap", "get"])
});

const RENDERER_TYPES = Object.freeze({
  sequential_list: "array",
  linked_list: "list",
  graph: "tree"
});

const ALLOWED_REQUEST_KEYS = new Set([
  "version", "structure", "operation", "params", "initial_state", "options", "source_ref", "context",
  "chapter_id", "chapterId", "lesson_id", "lessonId", "presentation_id", "presentationId",
  "presentation_page_id", "presentationPageId", "classroom_session_id", "classroomSessionId"
]);
const ALLOWED_CONTEXT_KEYS = new Set([
  "chapter_id", "lesson_id", "presentation_id", "presentation_page_id", "classroom_session_id",
  "source_type", "source_ref"
]);
const ALLOWED_ENVELOPE_KEYS = new Set(["protocol", "request"]);

class DsvpValidationError extends Error {
  constructor(code, message, detail = "") {
    super(message);
    this.name = "DsvpValidationError";
    this.code = code;
    this.detail = detail;
  }
}

function plainObject(value, path) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new DsvpValidationError("INVALID_REQUEST", `${path} must be an object`, path);
  }
  return value;
}

function scalar(value, path) {
  if (["string", "number", "boolean"].includes(typeof value) || value === null) {
    if (typeof value === "number" && !Number.isFinite(value)) {
      throw new DsvpValidationError("INVALID_VALUE", `${path} must be finite`, path);
    }
    return value;
  }
  throw new DsvpValidationError("INVALID_VALUE", `${path} must be scalar`, path);
}

function boundedText(value, max, fallback = "") {
  return String(value ?? fallback).trim().slice(0, max);
}

function contextText(value, field) {
  if (value == null || value === "") return "";
  if (typeof value !== "string" || value.trim().length > 160) {
    throw new DsvpValidationError("INVALID_CONTEXT", `${field} must be a bounded string`, field);
  }
  return value.trim();
}

function normalizedContext(request) {
  const context = request.context == null ? {} : plainObject(request.context, "request.context");
  const unexpected = Object.keys(context).filter((key) => !ALLOWED_CONTEXT_KEYS.has(key));
  if (unexpected.length) {
    throw new DsvpValidationError("UNEXPECTED_FIELD", "context contains unsupported fields", unexpected.join(","));
  }
  const aliases = {
    chapter_id: ["chapter_id", "chapterId"],
    lesson_id: ["lesson_id", "lessonId"],
    presentation_id: ["presentation_id", "presentationId"],
    presentation_page_id: ["presentation_page_id", "presentationPageId"],
    classroom_session_id: ["classroom_session_id", "classroomSessionId"]
  };
  const normalized = {};
  for (const [canonical, keys] of Object.entries(aliases)) {
    const direct = context[canonical];
    const fallback = keys.map((key) => request[key]).find((value) => value != null);
    const value = contextText(direct ?? fallback, `context.${canonical}`);
    if (value) normalized[canonical] = value;
  }
  for (const field of ["source_type", "source_ref"]) {
    const fallback = field === "source_ref" ? request.source_ref : undefined;
    const value = contextText(context[field] ?? fallback, `context.${field}`);
    if (value) normalized[field] = value;
  }
  return normalized;
}

function normalizeDsvpRequest(input) {
  const request = plainObject(input, "request");
  const unexpected = Object.keys(request).filter((key) => !ALLOWED_REQUEST_KEYS.has(key));
  if (unexpected.length) {
    throw new DsvpValidationError("UNEXPECTED_FIELD", "request contains unsupported fields", unexpected.join(","));
  }

  const version = boundedText(request.version, 16, DSVP_VERSION);
  if (version !== DSVP_VERSION) {
    throw new DsvpValidationError("UNSUPPORTED_VERSION", `unsupported DSVP version: ${version}`, version);
  }

  const structure = boundedText(request.structure, 32);
  const operation = boundedText(request.operation, 32);
  if (!SUPPORTED_OPERATIONS[structure]?.has(operation)) {
    throw new DsvpValidationError("UNSUPPORTED_OPERATION", `unsupported operation: ${structure}/${operation}`, `${structure}/${operation}`);
  }

  const params = request.params == null ? {} : plainObject(request.params, "request.params");
  const initialState = request.initial_state == null ? { data: [] } : plainObject(request.initial_state, "request.initial_state");
  const data = Array.isArray(initialState.data) ? initialState.data : null;
  if (!data) throw new DsvpValidationError("INVALID_INITIAL_STATE", "initial_state.data must be an array", "initial_state.data");
  if (data.length > 64) throw new DsvpValidationError("INITIAL_STATE_TOO_LARGE", "initial_state.data is too large", String(data.length));

  const metadata = initialState.metadata == null ? {} : plainObject(initialState.metadata, "request.initial_state.metadata");
  const rawCapacity = params.capacity ?? metadata.capacity ?? Math.max(10, data.length + 1);
  const capacity = Number(rawCapacity);
  if (!Number.isInteger(capacity) || capacity < 1 || capacity > 100) {
    throw new DsvpValidationError("INVALID_CAPACITY", "capacity must be an integer between 1 and 100", String(rawCapacity));
  }

  let normalizedData;
  if (structure === "hash") {
    normalizedData = (data.length ? data : Array.from({ length: 8 }, () => []))
      .slice(0, 16)
      .map((bucket, index) => {
        if (!Array.isArray(bucket)) throw new DsvpValidationError("INVALID_INITIAL_STATE", `hash bucket ${index} must be an array`, `initial_state.data[${index}]`);
        return bucket.slice(0, 8).map((entry, entryIndex) => {
          const item = plainObject(entry, `initial_state.data[${index}][${entryIndex}]`);
          const key = boundedText(item.key, 48);
          const val = boundedText(item.val, 48);
          if (!key) throw new DsvpValidationError("INVALID_VALUE", "hash entry key is required", `initial_state.data[${index}][${entryIndex}].key`);
          return { key, val };
        });
      });
  } else if (structure === "heap") {
    normalizedData = data.map((value, index) => {
      if (typeof value !== "number" || !Number.isFinite(value)) {
        throw new DsvpValidationError("INVALID_VALUE", "heap values must be finite numbers", `initial_state.data[${index}]`);
      }
      return value;
    });
  } else if (structure === "graph") {
    normalizedData = data.map((value, index) => scalar(value, `initial_state.data[${index}]`));
  } else if (structure === "sequential_list" && operation === "merge") {
    if (data.length !== 2 || data.some((items) => !Array.isArray(items))) {
      throw new DsvpValidationError("INVALID_INITIAL_STATE", "merge requires two list arrays", "initial_state.data");
    }
    normalizedData = data.map((items, listIndex) => items.slice(0, 32).map((value, index) => scalar(value, `initial_state.data[${listIndex}][${index}]`)));
  } else {
    normalizedData = data.map((value, index) => scalar(value, `initial_state.data[${index}]`));
  }

  if (structure !== "sequential_list" || operation !== "merge") {
    if (normalizedData.length > capacity) throw new DsvpValidationError("INITIAL_STATE_OVERFLOW", "initial state exceeds capacity", `${normalizedData.length}/${capacity}`);
  } else if (normalizedData[0].length + normalizedData[1].length > capacity) {
    throw new DsvpValidationError("INITIAL_STATE_OVERFLOW", "merged lists exceed capacity", `${normalizedData[0].length + normalizedData[1].length}/${capacity}`);
  }

  const valueRequired = new Set(["push", "enqueue", "insert", "put"]);
  if (valueRequired.has(operation) && operation !== "put" && params.value === undefined) {
    throw new DsvpValidationError("MISSING_VALUE", `${operation} requires params.value`, "params.value");
  }
  if (operation === "put" && (!params.key || params.val === undefined)) {
    throw new DsvpValidationError("MISSING_VALUE", "put requires params.key and params.val", "params");
  }
  const position = params.position == null ? undefined : Number(params.position);
  if (["sequential_list", "linked_list"].includes(structure) && ["insert", "delete"].includes(operation)) {
    if (!Number.isInteger(position) || position < 1 || position > (operation === "insert" ? normalizedData.length + 1 : normalizedData.length)) {
      throw new DsvpValidationError("INVALID_POSITION", "position is outside the valid range", "params.position");
    }
  }
  const options = request.options == null ? {} : plainObject(request.options, "request.options");
  const context = normalizedContext(request);
  const optionalIntegers = {};
  for (const field of ["node", "index", "i", "j"]) {
    if (params[field] === undefined) continue;
    const number = Number(params[field]);
    if (!Number.isInteger(number) || number < 0 || number > 1024) {
      throw new DsvpValidationError("INVALID_VALUE", `${field} must be a non-negative integer`, `params.${field}`);
    }
    optionalIntegers[field] = number;
  }
  return {
    version,
    structure,
    operation,
    params: {
      ...(params.value !== undefined ? { value: scalar(params.value, "params.value") } : {}),
      ...(params.key !== undefined ? { key: boundedText(params.key, 48) } : {}),
      ...(params.val !== undefined ? { val: boundedText(params.val, 48) } : {}),
      ...optionalIntegers,
      ...(position !== undefined ? { position } : {}),
      capacity
    },
    initial_state: { data: normalizedData, metadata: { capacity } },
    options: {
      language: boundedText(options.language, 16, "c"),
      explain_level: boundedText(options.explain_level, 24, "beginner")
    },
    source_ref: boundedText(request.source_ref, 160),
    ...(Object.keys(context).length ? { context } : {})
  };
}

function rendererType(structure) {
  return RENDERER_TYPES[structure] || structure;
}

function clone(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

function step(op, label, note, fields = {}) {
  return { op, label: boundedText(label, 48, op), note: boundedText(note, 240, label), ...fields };
}

function animationDataForRequest(request) {
  const { structure, operation, params } = request;
  const type = rendererType(structure);
  let initial = clone(request.initial_state.data);
  let steps;
  if (structure === "sequential_list" && operation === "merge") {
    const [left, right] = request.initial_state.data;
    initial = clone(left);
    steps = right.length
      ? right.slice(0, 20).map((value, index) => step(
        "insert",
        "merge",
        "Insert the next ordered value",
        { value, index: left.length + index }
      ))
      : [step("get", "merge", "Inspect the already merged sequence", { index: 0 })];
  }
  const fields = {};
  if (structure === "tree" || structure === "graph") fields.node = params.node;
  let op = operation;
  if (structure === "sequential_list") op = operation === "merge" ? "insert" : operation;
  if (structure === "linked_list") op = operation === "append" ? "append" : operation;
  if (structure === "graph") op = operation === "visit" ? "visit" : "highlight";
  if (params.value !== undefined) fields.value = params.value;
  if (params.position !== undefined) fields.index = Math.max(0, params.position - 1);
  if (params.index !== undefined) fields.index = params.index;
  if (params.key !== undefined) fields.key = params.key;
  if (params.val !== undefined) fields.val = params.val;
  if (params.node !== undefined) fields.node = Number(params.node);
  if (params.i !== undefined) fields.i = Number(params.i);
  if (params.j !== undefined) fields.j = Number(params.j);
  if (!steps) {
    steps = [step(op, operation, `Execute ${structure} ${operation}`, fields)];
  }
  return {
    animation: true,
    type,
    title: `${structure} ${operation}`.slice(0, 60),
    description: `DSVP ${DSVP_VERSION} ${structure}/${operation}`.slice(0, 240),
    initial,
    steps
  };
}

function traceForRequest(request, animationData) {
  const traceId = `dsvp_${crypto.createHash("sha256").update(JSON.stringify(request)).digest("hex").slice(0, 20)}`;
  return {
    version: DSVP_VERSION,
    protocol: `dsvp/${DSVP_VERSION}`,
    trace_id: traceId,
    structure: request.structure,
    operation: request.operation,
    source_ref: request.source_ref,
    steps: animationData.steps.map((item, index) => ({
      step_id: index + 1,
      phase: index === 0 ? "operation" : "done",
      title: item.label,
      description: item.note,
      state: { kind: request.structure, data: clone(request.initial_state.data), metadata: request.initial_state.metadata },
      actions: [{ type: item.op, value: item.value ?? null }]
    })),
    errors: [],
    warnings: []
  };
}

function adaptDsvp(input) {
  let raw = input;
  if (input && typeof input === "object" && input.request && typeof input.request === "object") {
    const envelope = plainObject(input, "envelope");
    const unexpected = Object.keys(envelope).filter((key) => !ALLOWED_ENVELOPE_KEYS.has(key));
    if (unexpected.length) {
      throw new DsvpValidationError("UNEXPECTED_FIELD", "envelope contains unsupported fields", unexpected.join(","));
    }
    const protocol = boundedText(envelope.protocol, 16, DSVP_PROTOCOL);
    if (!SUPPORTED_PROTOCOLS.has(protocol)) {
      throw new DsvpValidationError("UNSUPPORTED_PROTOCOL", `unsupported DSVP protocol: ${protocol}`, protocol);
    }
    raw = envelope.request;
  }
  const request = normalizeDsvpRequest(raw);
  const animationData = animationDataForRequest(request);
  const trace = traceForRequest(request, animationData);
  return { protocol: DSVP_PROTOCOL, request, trace, animationData };
}

module.exports = {
  DSVP_VERSION,
  DSVP_PROTOCOL,
  SUPPORTED_OPERATIONS,
  DsvpValidationError,
  normalizeDsvpRequest,
  adaptDsvp
};
