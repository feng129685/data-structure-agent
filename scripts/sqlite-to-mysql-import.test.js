const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { afterEach, test } = require("node:test");
const Database = require("better-sqlite3");

const scriptPath = path.join(__dirname, "sqlite-to-mysql-import.js");
const temporaryDirectories = [];

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("defaults to a read-only dry-run and reports fields without a safe MySQL mapping", () => {
  const fixture = createLegacyDatabase();
  const before = sha256(fixture.databasePath);

  const result = runTool("--sqlite", fixture.databasePath);

  assert.equal(result.status, 0, result.stderr);
  assert.equal(sha256(fixture.databasePath), before);
  const report = JSON.parse(result.stdout);
  assert.equal(report.mode, "dry-run");
  assert.equal(report.source.openMode, "readonly");
  assert.equal(report.outputWritten, false);
  assert.deepEqual(report.rowCounts, {
    chat_threads: 1,
    conversations: 0,
    learning_snapshots: 1,
    teacher_assignments: 1,
    users: 1
  });
  assert.ok(report.unmappedFields.some((item) => item.field === "users.password_hash"));
  assert.ok(report.unmappedFields.some((item) => item.field === "users.teacher_or_admin_role"));
  assert.ok(report.unmappedFields.some((item) => item.field === "chat_threads.classroom_state"));
  assert.ok(report.unmappedFields.some((item) => item.field === "chat_threads.messages[].animationData"));
  assert.ok(report.unmappedFields.some((item) => item.field === "conversations.created_at"));
  assert.ok(report.unmappedFields.some((item) => item.field === "learning_snapshots.learning_progress"));
  assert.ok(report.unmappedFields.some((item) => item.field === "teacher_assignments.*"));
});

test("blocks sessions without trustworthy chronology instead of substituting import time", () => {
  const fixture = createLegacyDatabase();
  const db = new Database(fixture.databasePath);
  db.prepare("INSERT INTO conversations VALUES (?, ?, ?, ?, ?)").run(
    9,
    7,
    "queue",
    JSON.stringify([{ role: "user", content: "When was this asked?" }]),
    null
  );
  db.close();
  const sqlPath = path.join(fixture.directory, "chronology-import.sql");

  const result = runTool(
    "--sqlite", fixture.databasePath,
    "--emit-sql", sqlPath,
    "--target", "staging",
    "--backup-confirmed"
  );

  assert.equal(result.status, 0, result.stderr);
  const report = JSON.parse(result.stdout);
  assert.ok(report.blockedRows.some((item) =>
    item.table === "conversations" && item.key === "9" && /timestamp/i.test(item.reason)
  ));
  const sql = fs.readFileSync(sqlPath, "utf8");
  assert.doesNotMatch(sql, /CURRENT_TIMESTAMP/);
});

test("requires a verified backup and rejects every production or database-file output", () => {
  const fixture = createLegacyDatabase();
  const sqlPath = path.join(fixture.directory, "legacy-import.sql");

  const missingBackup = runTool(
    "--sqlite", fixture.databasePath,
    "--emit-sql", sqlPath,
    "--target", "staging"
  );
  assert.equal(missingBackup.status, 2);
  assert.match(missingBackup.stderr, /--backup-confirmed/);
  assert.equal(fs.existsSync(sqlPath), false);

  const production = runTool(
    "--sqlite", fixture.databasePath,
    "--emit-sql", sqlPath,
    "--target", "production",
    "--backup-confirmed"
  );
  assert.equal(production.status, 2);
  assert.match(production.stderr, /production/i);
  assert.equal(fs.existsSync(sqlPath), false);

  const databaseOutput = runTool(
    "--sqlite", fixture.databasePath,
    "--emit-sql", path.join(fixture.directory, "data.db"),
    "--target", "staging",
    "--backup-confirmed"
  );
  assert.equal(databaseOutput.status, 2);
  assert.match(databaseOutput.stderr, /\.sql/);
});

test("emits a staging-only SQL bundle inside one transaction without legacy password hashes", () => {
  const fixture = createLegacyDatabase();
  const before = sha256(fixture.databasePath);
  const sqlPath = path.join(fixture.directory, "legacy-import.sql");

  const result = runTool(
    "--sqlite", fixture.databasePath,
    "--emit-sql", sqlPath,
    "--target", "staging",
    "--backup-confirmed"
  );

  assert.equal(result.status, 0, result.stderr);
  assert.equal(sha256(fixture.databasePath), before);
  const report = JSON.parse(result.stdout);
  assert.equal(report.mode, "sql-bundle");
  assert.equal(report.target, "staging");
  assert.equal(report.outputWritten, true);

  const sql = fs.readFileSync(sqlPath, "utf8");
  assert.match(sql, /START TRANSACTION;/);
  assert.match(sql, /COMMIT;/);
  assert.equal((sql.match(/START TRANSACTION;/g) || []).length, 1);
  assert.equal((sql.match(/COMMIT;/g) || []).length, 1);
  assert.match(sql, /'!RESET_REQUIRED!', 'ACTIVE'/);
  assert.doesNotMatch(sql, /'PASSWORD_RESET_REQUIRED'/);
  assert.match(sql, /INSERT INTO chat_sessions/);
  assert.match(sql, /INSERT INTO chat_messages/);
  assert.match(sql, /INSERT INTO user_roles \(user_id, role\) VALUES \(7, 'STUDENT'\);/);
  assert.match(sql, /UNMAPPED users\.password_hash/);
  assert.doesNotMatch(sql, /legacy-scrypt-secret/);
  assert.doesNotMatch(sql, /SQLite format 3/);
  assert.doesNotMatch(sql, new RegExp(escapeRegExp(path.basename(fixture.databasePath)), "i"));
});

function createLegacyDatabase() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "dsagent-sqlite-mysql-"));
  temporaryDirectories.push(directory);
  const databasePath = path.join(directory, "legacy.sqlite");
  const db = new Database(databasePath);
  db.exec(`
    CREATE TABLE users (
      id INTEGER PRIMARY KEY,
      email TEXT NOT NULL,
      password_hash TEXT,
      created_at TEXT
    );
    CREATE TABLE conversations (
      id INTEGER PRIMARY KEY,
      user_id INTEGER NOT NULL,
      scenario TEXT NOT NULL,
      messages TEXT NOT NULL,
      updated_at TEXT
    );
    CREATE TABLE chat_threads (
      id TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL,
      type TEXT NOT NULL,
      scenario TEXT NOT NULL,
      title TEXT NOT NULL,
      messages TEXT NOT NULL,
      classroom_state TEXT,
      created_at TEXT,
      updated_at TEXT
    );
    CREATE TABLE learning_snapshots (
      user_id INTEGER PRIMARY KEY,
      learning_progress TEXT NOT NULL,
      weak_memory TEXT NOT NULL,
      teacher_tasks TEXT NOT NULL,
      report TEXT NOT NULL,
      stats TEXT NOT NULL,
      updated_at TEXT
    );
    CREATE TABLE teacher_assignments (
      id TEXT PRIMARY KEY,
      teacher_id INTEGER NOT NULL,
      scenario TEXT NOT NULL,
      topic TEXT NOT NULL,
      title TEXT NOT NULL,
      description TEXT NOT NULL,
      steps TEXT NOT NULL,
      target_student_ids TEXT NOT NULL,
      status TEXT NOT NULL,
      created_at TEXT,
      updated_at TEXT
    );
  `);
  db.prepare("INSERT INTO users VALUES (?, ?, ?, ?)").run(
    7,
    "legacy@example.com",
    "legacy-scrypt-secret",
    "2026-08-01 09:00:00"
  );
  db.prepare("INSERT INTO chat_threads VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
    "thread-7",
    7,
    "coach",
    "stack",
    "Legacy stack chat",
    JSON.stringify([
      { role: "user", content: "Why is this LIFO?", animationData: { type: "stack" } },
      { role: "assistant", content: "The last pushed item leaves first." }
    ]),
    JSON.stringify({ phase: "question" }),
    "2026-08-01 09:05:00",
    "2026-08-01 09:06:00"
  );
  db.prepare("INSERT INTO learning_snapshots VALUES (?, ?, ?, ?, ?, ?, ?)").run(
    7,
    JSON.stringify({ stack: 80 }),
    "[]",
    "{}",
    "{}",
    "{}",
    "2026-08-01 09:07:00"
  );
  db.prepare("INSERT INTO teacher_assignments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
    "assignment-1",
    7,
    "stack",
    "LIFO",
    "Review stack",
    "Practice stack operations",
    "[]",
    "[]",
    "active",
    "2026-08-01 09:08:00",
    "2026-08-01 09:09:00"
  );
  db.close();
  return { directory, databasePath };
}

function runTool(...args) {
  return spawnSync(process.execPath, [scriptPath, ...args], {
    cwd: path.join(__dirname, ".."),
    encoding: "utf8"
  });
}

function sha256(filePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
