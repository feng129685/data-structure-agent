const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const root = path.join(__dirname, "..");
const nodeRoot = path.join(root, "backend", "node");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-agent-dsvp-"));
const dbPath = path.join(tempDir, "data.db");
const secret = "dsvp-test-secret-32-characters-long";
const port = 18_791 + Math.floor(Math.random() * 200);
const origin = "https://structify.cn";

function tokenFor(userId, email) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const header = encode({ alg: "HS256", typ: "JWT" });
  const payload = encode({ userId, email, iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600 });
  const signature = crypto.createHmac("sha256", secret).update(`${header}.${payload}`).digest("base64url");
  return `${header}.${payload}.${signature}`;
}

async function waitForHealth(baseUrl, child) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    if (child.exitCode !== null) throw new Error(`server exited with ${child.exitCode}`);
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("server did not become ready");
}

async function main() {
  const child = spawn(process.execPath, ["server.js"], {
    cwd: nodeRoot,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: dbPath,
      JWT_SECRET: secret,
      CORS_ALLOWED_ORIGINS: origin,
      MODEL_API_KEY: ""
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let stderr = "";
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await waitForHealth(baseUrl, child);

    const guest = await fetch(`${baseUrl}/api/animation/simulate`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({})
    });
    assert.equal(guest.status, 401);

    const Database = require("better-sqlite3");
    const db = new Database(dbPath);
    db.prepare("INSERT INTO users (email, password_hash) VALUES (?, ?)").run("dsvp@example.test", "not-used");
    db.close();
    const auth = { authorization: `Bearer ${tokenFor(1, "dsvp@example.test")}`, origin };
    const response = await fetch(`${baseUrl}/api/animation/simulate`, {
      method: "POST",
      headers: { ...auth, "content-type": "application/json" },
      body: JSON.stringify({
        version: "1.0",
        structure: "stack",
        operation: "push",
        params: { value: 3 },
        initial_state: { data: [1, 2], metadata: { capacity: 8 } },
        source_ref: "test/stack"
      })
    });
    assert.equal(response.status, 200);
    const payload = await response.json();
    assert.equal(payload.protocol, "dsvp/1.0");
    assert.equal(payload.animationData.type, "stack");
    assert.equal(payload.animationData.steps[0].op, "push");
    assert.equal(response.headers.get("access-control-allow-origin"), origin);

    const invalid = await fetch(`${baseUrl}/api/animation/simulate`, {
      method: "POST",
      headers: { ...auth, "content-type": "application/json" },
      body: JSON.stringify({ version: "1.0", structure: "stack", operation: "execute", initial_state: { data: [] } })
    });
    assert.equal(invalid.status, 400);
    assert.equal((await invalid.json()).code, "UNSUPPORTED_OPERATION");

    const preflight = await fetch(`${baseUrl}/api/animation/simulate`, {
      method: "OPTIONS",
      headers: { origin, "access-control-request-method": "POST" }
    });
    assert.equal(preflight.status, 204);
    assert.equal(preflight.headers.get("access-control-allow-origin"), origin);

    console.log("dsvp-api-ok auth=required cors=exact bounded=checked");
  } finally {
    child.kill();
    await new Promise((resolve) => child.once("exit", resolve));
    if (stderr.includes(secret)) throw new Error("test secret leaked to server stderr");
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`dsvp-api-failed: ${error.message}`);
  process.exitCode = 1;
});
