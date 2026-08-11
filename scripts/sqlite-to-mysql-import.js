#!/usr/bin/env node

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const Database = require("better-sqlite3");

const SOURCE_TABLES = [
  "chat_threads",
  "conversations",
  "learning_snapshots",
  "teacher_assignments",
  "users"
];
const NON_PRODUCTION_TARGETS = new Set(["development", "test", "staging"]);
const STATIC_UNMAPPED_FIELDS = [
  {
    field: "users.password_hash",
    reason: "Node uses scrypt while Spring uses BCrypt; the hash is replaced by a non-matching reset sentinel."
  },
  {
    field: "users.teacher_or_admin_role",
    reason: "SQLite stores no durable roles; imports receive STUDENT only and privileged roles require separate review."
  },
  {
    field: "chat_threads.type",
    reason: "chat_sessions has no typed conversation-kind column."
  },
  {
    field: "chat_threads.scenario",
    reason: "Legacy scenarios cannot be assumed to be valid chapter foreign keys."
  },
  {
    field: "chat_threads.classroom_state",
    reason: "A classroom session requires a reviewed Spring classroom script and cannot be reconstructed safely."
  },
  {
    field: "conversations.scenario",
    reason: "The value is retained only in the generated session title, not as a chapter foreign key."
  },
  {
    field: "conversations.created_at",
    reason: "The source table has no creation timestamp; a valid updated_at is reused and this limitation is disclosed."
  },
  {
    field: "conversations.messages[].created_at",
    reason: "Legacy messages have no individual timestamps; the valid conversation updated_at is reused."
  },
  {
    field: "chat_threads.messages[].created_at",
    reason: "Legacy messages have no individual timestamps; the valid thread created_at is reused."
  },
  {
    field: "learning_snapshots.learning_progress",
    reason: "The aggregate JSON has no lossless event-level mapping to learning_records."
  },
  {
    field: "learning_snapshots.weak_memory",
    reason: "The aggregate JSON has no lossless event-level mapping to learning_records."
  },
  {
    field: "learning_snapshots.teacher_tasks",
    reason: "Spring has no equivalent persisted teacher-task aggregate."
  },
  {
    field: "learning_snapshots.report",
    reason: "The derived report must be rebuilt from migrated evidence."
  },
  {
    field: "learning_snapshots.stats",
    reason: "The derived statistics must be rebuilt from migrated evidence."
  },
  {
    field: "teacher_assignments.*",
    reason: "V11 has no reviewed Spring assignment aggregate, so assignment rows are audit-only."
  }
];

function main() {
  let options;
  try {
    options = parseArguments(process.argv.slice(2));
    if (options.help) {
      process.stdout.write(usage());
      return;
    }
    validateOptions(options);
  } catch (error) {
    fail(error.message, 2);
    return;
  }

  let audit;
  try {
    audit = auditDatabase(options.sqlitePath);
  } catch (error) {
    fail(`SQLite audit failed: ${error.message}`, 1);
    return;
  }

  if (!options.outputPath) {
    process.stdout.write(JSON.stringify(buildReport(audit, options, false), null, 2) + "\n");
    return;
  }

  try {
    const sql = buildSqlBundle(audit, options.target);
    fs.writeFileSync(options.outputPath, sql, { encoding: "utf8", flag: "wx", mode: 0o600 });
  } catch (error) {
    fail(`SQL bundle was not written: ${error.message}`, 1);
    return;
  }
  process.stdout.write(JSON.stringify(buildReport(audit, options, true), null, 2) + "\n");
}

function parseArguments(args) {
  const options = {
    sqlitePath: path.resolve(__dirname, "..", "private", "state", "node", "data.db"),
    outputPath: null,
    target: null,
    backupConfirmed: false,
    help: false
  };
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--help" || argument === "-h") {
      options.help = true;
    } else if (argument === "--backup-confirmed") {
      options.backupConfirmed = true;
    } else if (argument === "--sqlite") {
      options.sqlitePath = path.resolve(requiredValue(args, ++index, argument));
    } else if (argument === "--emit-sql") {
      options.outputPath = path.resolve(requiredValue(args, ++index, argument));
    } else if (argument === "--target") {
      options.target = requiredValue(args, ++index, argument).toLowerCase();
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return options;
}

function requiredValue(args, index, option) {
  const value = args[index];
  if (!value || value.startsWith("--")) {
    throw new Error(`${option} requires a value.`);
  }
  return value;
}

function validateOptions(options) {
  if (!fs.existsSync(options.sqlitePath) || !fs.statSync(options.sqlitePath).isFile()) {
    throw new Error(`SQLite source does not exist or is not a file: ${options.sqlitePath}`);
  }
  if (!options.outputPath) {
    return;
  }
  if (!options.backupConfirmed) {
    throw new Error("SQL generation requires --backup-confirmed after a restore-tested MySQL backup.");
  }
  if (!options.target || !NON_PRODUCTION_TARGETS.has(options.target)) {
    const production = options.target && /^(prod|production|live)$/i.test(options.target);
    throw new Error(production
      ? "Production targets are prohibited; generate and validate the bundle against staging first."
      : "--target must be one of: development, test, staging. Production is not supported.");
  }
  if (path.extname(options.outputPath).toLowerCase() !== ".sql") {
    throw new Error("--emit-sql must point to a new .sql file; database files are never emitted or copied.");
  }
  if (path.resolve(options.outputPath) === path.resolve(options.sqlitePath)) {
    throw new Error("The SQL output cannot replace the SQLite source.");
  }
  if (fs.existsSync(options.outputPath)) {
    throw new Error("Refusing to overwrite an existing SQL bundle.");
  }
  const outputParent = path.dirname(options.outputPath);
  if (!fs.existsSync(outputParent) || !fs.statSync(outputParent).isDirectory()) {
    throw new Error("The SQL output directory must already exist.");
  }
}

function auditDatabase(sqlitePath) {
  const sourceHash = sha256File(sqlitePath);
  const db = new Database(sqlitePath, { readonly: true, fileMustExist: true });
  try {
    db.pragma("query_only = ON");
    const availableTables = new Set(db.prepare(
      "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
    ).pluck().all());
    const rowCounts = Object.fromEntries(SOURCE_TABLES.map((table) => [
      table,
      availableTables.has(table) ? db.prepare(`SELECT COUNT(*) FROM ${quoteIdentifier(table)}`).pluck().get() : 0
    ]));
    const missingTables = SOURCE_TABLES.filter((table) => !availableTables.has(table));
    const unmappedFields = [...STATIC_UNMAPPED_FIELDS];
    const blockedRows = [];
    const users = readUsers(db, availableTables, blockedRows);
    const userIds = new Set(users.map((user) => user.id));
    const sessions = [];
    const messages = [];

    readChatThreads(db, availableTables, userIds, sessions, messages, unmappedFields, blockedRows);
    readConversations(db, availableTables, userIds, sessions, messages, unmappedFields, blockedRows);

    return {
      sourceHash,
      rowCounts,
      missingTables,
      unmappedFields: uniqueFields(unmappedFields),
      blockedRows,
      users,
      sessions,
      messages
    };
  } finally {
    db.close();
  }
}

function readUsers(db, availableTables, blockedRows) {
  if (!availableTables.has("users")) {
    return [];
  }
  const columns = columnNames(db, "users");
  requireColumns("users", columns, ["id", "email"]);
  const createdExpression = columns.has("created_at") ? "created_at" : "NULL AS created_at";
  const rows = db.prepare(`SELECT id, email, ${createdExpression} FROM users ORDER BY id`).all();
  const users = [];
  for (const row of rows) {
    if (!Number.isSafeInteger(row.id) || row.id <= 0) {
      blockedRows.push({ table: "users", key: String(row.id), reason: "id is not a positive safe integer" });
      continue;
    }
    const email = typeof row.email === "string" ? row.email.trim().toLowerCase() : "";
    if (!email || Buffer.byteLength(email, "utf8") > 254) {
      blockedRows.push({ table: "users", key: String(row.id), reason: "email is empty or exceeds 254 bytes" });
      continue;
    }
    const createdAt = normalizeTimestamp(row.created_at);
    if (!createdAt) {
      blockedRows.push({ table: "users", key: String(row.id), reason: "created_at is missing or is not a valid timestamp" });
      continue;
    }
    users.push({ id: row.id, email, createdAt });
  }
  return users;
}

function readChatThreads(db, availableTables, userIds, sessions, messages, unmappedFields, blockedRows) {
  if (!availableTables.has("chat_threads")) {
    return;
  }
  const columns = columnNames(db, "chat_threads");
  requireColumns("chat_threads", columns, ["id", "user_id", "title", "messages"]);
  const optional = (name) => columns.has(name) ? name : `NULL AS ${name}`;
  const rows = db.prepare(`
    SELECT id, user_id, title, messages, ${optional("created_at")}, ${optional("updated_at")}
    FROM chat_threads
    ORDER BY id
  `).all();
  for (const row of rows) {
    const id = stringValue(row.id);
    if (!validIdentifier(id, 64) || !userIds.has(row.user_id)) {
      blockedRows.push({ table: "chat_threads", key: id, reason: "invalid id or owner was not migratable" });
      continue;
    }
    const createdAt = normalizeTimestamp(row.created_at);
    const updatedAt = normalizeTimestamp(row.updated_at);
    if (!createdAt || !updatedAt) {
      blockedRows.push({ table: "chat_threads", key: id, reason: "created_at or updated_at is not a valid timestamp" });
      continue;
    }
    const parsed = parseMessages(row.messages, "chat_threads", id, unmappedFields, blockedRows);
    if (!parsed) {
      continue;
    }
    sessions.push({
      id,
      userId: row.user_id,
      title: truncate(stringValue(row.title) || "Legacy conversation", 200),
      createdAt,
      updatedAt
    });
    appendMessages(messages, id, parsed, createdAt);
  }
}

function readConversations(db, availableTables, userIds, sessions, messages, unmappedFields, blockedRows) {
  if (!availableTables.has("conversations")) {
    return;
  }
  const columns = columnNames(db, "conversations");
  requireColumns("conversations", columns, ["id", "user_id", "scenario", "messages"]);
  const updatedExpression = columns.has("updated_at") ? "updated_at" : "NULL AS updated_at";
  const rows = db.prepare(`
    SELECT id, user_id, scenario, messages, ${updatedExpression}
    FROM conversations
    ORDER BY id
  `).all();
  for (const row of rows) {
    const id = `legacy-conversation-${row.id}`;
    if (!Number.isSafeInteger(row.id) || row.id <= 0 || !validIdentifier(id, 64) || !userIds.has(row.user_id)) {
      blockedRows.push({ table: "conversations", key: String(row.id), reason: "invalid id or owner was not migratable" });
      continue;
    }
    const updatedAt = normalizeTimestamp(row.updated_at);
    if (!updatedAt) {
      blockedRows.push({ table: "conversations", key: String(row.id), reason: "updated_at is missing or is not a valid timestamp" });
      continue;
    }
    const parsed = parseMessages(row.messages, "conversations", String(row.id), unmappedFields, blockedRows);
    if (!parsed) {
      continue;
    }
    sessions.push({
      id,
      userId: row.user_id,
      title: truncate(`Legacy ${stringValue(row.scenario) || "conversation"}`, 200),
      createdAt: updatedAt,
      updatedAt
    });
    appendMessages(messages, id, parsed, updatedAt);
  }
}

function parseMessages(value, table, key, unmappedFields, blockedRows) {
  let parsed;
  try {
    parsed = JSON.parse(value);
  } catch {
    blockedRows.push({ table, key, reason: "messages is not valid JSON" });
    return null;
  }
  if (!Array.isArray(parsed)) {
    blockedRows.push({ table, key, reason: "messages is not a JSON array" });
    return null;
  }
  const result = [];
  for (const message of parsed) {
    if (!message || typeof message !== "object" || Array.isArray(message)) {
      continue;
    }
    for (const field of Object.keys(message)) {
      if (field !== "role" && field !== "content") {
        unmappedFields.push({
          field: `${table}.messages[].${field}`,
          reason: "chat_messages has no compatible typed column for this legacy message field."
        });
      }
    }
    const role = stringValue(message.role).toLowerCase();
    const content = stringValue(message.content);
    if (!["assistant", "system", "user"].includes(role) || !content) {
      continue;
    }
    result.push({ role, content });
  }
  return result;
}

function appendMessages(destination, sessionId, messages, createdAt) {
  for (const message of messages) {
    destination.push({ sessionId, role: message.role, content: message.content, createdAt });
  }
}

function columnNames(db, table) {
  return new Set(db.pragma(`table_info(${JSON.stringify(table)})`).map((column) => column.name));
}

function requireColumns(table, actual, required) {
  const missing = required.filter((column) => !actual.has(column));
  if (missing.length) {
    throw new Error(`${table} is missing required columns: ${missing.join(", ")}`);
  }
}

function uniqueFields(fields) {
  const byField = new Map();
  for (const item of fields) {
    if (!byField.has(item.field)) {
      byField.set(item.field, item);
    }
  }
  return [...byField.values()].sort((left, right) => left.field.localeCompare(right.field));
}

function buildReport(audit, options, outputWritten) {
  return {
    schemaVersion: 1,
    mode: outputWritten ? "sql-bundle" : "dry-run",
    source: {
      openMode: "readonly",
      sha256: audit.sourceHash,
      databaseIncluded: false
    },
    target: options.target,
    outputWritten,
    rowCounts: audit.rowCounts,
    plannedRows: {
      users: audit.users.length,
      user_roles: audit.users.length,
      chat_sessions: audit.sessions.length,
      chat_messages: audit.messages.length
    },
    missingTables: audit.missingTables,
    blockedRows: audit.blockedRows,
    unmappedFields: audit.unmappedFields,
    safeguards: {
      directDatabaseConnection: false,
      productionTargetSupported: false,
      backupConfirmed: options.backupConfirmed,
      importedAccounts: "ACTIVE with a non-matching hash so the existing password-reset flow remains reachable",
      transaction: outputWritten ? "one START TRANSACTION/COMMIT block" : "not emitted in dry-run"
    }
  };
}

function buildSqlBundle(audit, target) {
  const lines = [
    "-- SQLite to MySQL staging import bundle",
    `-- Target class: ${target}`,
    "-- PRECONDITION: restore-tested MySQL backup confirmed by the operator.",
    "-- The SQLite database file and legacy password hashes are intentionally excluded.",
    "-- Imported accounts are locked until a Spring password-reset flow assigns a BCrypt hash.",
    ...audit.unmappedFields.map((item) => `-- UNMAPPED ${item.field}: ${item.reason}`),
    "",
    "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;",
    "START TRANSACTION;",
    ""
  ];

  for (const user of audit.users) {
    const createdAt = timestampExpression(user.createdAt);
    lines.push(
      "INSERT INTO users (id, email, password_hash, status, created_at, updated_at) VALUES " +
      `(${user.id}, ${utf8Expression(user.email)}, '!RESET_REQUIRED!', 'ACTIVE', ${createdAt}, ${createdAt});`
    );
    lines.push(`INSERT INTO user_roles (user_id, role) VALUES (${user.id}, 'STUDENT');`);
  }
  for (const session of audit.sessions) {
    lines.push(
      "INSERT INTO chat_sessions (id, user_id, chapter_id, title, created_at, updated_at) VALUES " +
      `(${utf8Expression(session.id)}, ${session.userId}, NULL, ${utf8Expression(session.title)}, ` +
      `${timestampExpression(session.createdAt)}, ${timestampExpression(session.updatedAt)});`
    );
  }
  for (const message of audit.messages) {
    lines.push(
      "INSERT INTO chat_messages (session_id, role, content, sources_json, created_at) VALUES " +
      `(${utf8Expression(message.sessionId)}, '${message.role}', ${utf8Expression(message.content)}, NULL, ` +
      `${timestampExpression(message.createdAt)});`
    );
  }

  lines.push("", "COMMIT;", "");
  return lines.join("\n");
}

function utf8Expression(value) {
  return `CONVERT(0x${Buffer.from(String(value), "utf8").toString("hex")} USING utf8mb4)`;
}

function timestampExpression(value) {
  if (!value) {
    throw new Error("Refusing to fabricate a missing source timestamp.");
  }
  return `'${value}'`;
}

function normalizeTimestamp(value) {
  const text = stringValue(value).trim();
  if (!text) {
    return null;
  }
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(text)) {
    return text;
  }
  const parsed = new Date(text);
  if (Number.isNaN(parsed.getTime())) {
    return null;
  }
  return parsed.toISOString().slice(0, 19).replace("T", " ");
}

function stringValue(value) {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}

function truncate(value, maximumLength) {
  return [...value].slice(0, maximumLength).join("");
}

function validIdentifier(value, maximumBytes) {
  return Boolean(value) && Buffer.byteLength(value, "utf8") <= maximumBytes;
}

function quoteIdentifier(identifier) {
  if (!SOURCE_TABLES.includes(identifier)) {
    throw new Error(`Unexpected table identifier: ${identifier}`);
  }
  return `"${identifier}"`;
}

function sha256File(filePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

function usage() {
  return [
    "Usage: node scripts/sqlite-to-mysql-import.js [options]",
    "",
    "Default behavior is a read-only dry-run audit. No file is written.",
    "",
    "Options:",
    "  --sqlite <file>          SQLite source (defaults to private/state/node/data.db)",
    "  --emit-sql <new.sql>     Generate SQL; never connects to MySQL",
    "  --target <environment>   development, test, or staging only",
    "  --backup-confirmed       Confirm a restore-tested MySQL backup exists",
    "  --help                    Show this help",
    "",
    "Production targets and database-file outputs are always rejected.",
    "Every SQL bundle uses one transaction and lists fields that cannot migrate safely.",
    ""
  ].join("\n");
}

function fail(message, exitCode) {
  process.stderr.write(`ERROR: ${message}\n`);
  process.exitCode = exitCode;
}

if (require.main === module) {
  main();
}
