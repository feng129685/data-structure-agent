const assert = require("node:assert/strict");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const root = path.join(__dirname, "..");
const nodeRoot = path.join(root, "backend", "node");
const testJwtSecret = "node-production-debug-knowledge-test-secret";

function getFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForHealth(baseUrl, child, stderrRef) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`server exited early (${child.exitCode})\\n${stderrRef.value}`);
    }
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch {}
    await wait(120);
  }
  throw new Error(`server health timeout\\n${stderrRef.value}`);
}

async function stop(child) {
  child.kill();
  if (child.exitCode !== null) return;
  await Promise.race([
    new Promise((resolve) => child.once("exit", resolve)),
    wait(1_500)
  ]);
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-agent-production-debug-knowledge-"));
  const port = await getFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const stderrRef = { value: "" };
  const child = spawn(process.execPath, ["server.js"], {
    cwd: nodeRoot,
    env: {
      ...process.env,
      NODE_ENV: "production",
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: path.join(tempDir, "data.db"),
      NODE_STATE_DIR: path.join(tempDir, "state"),
      KNOWLEDGE_DIR: path.join(tempDir, "knowledge"),
      NODE_COMPAT_JWT_SECRET: testJwtSecret,
      CORS_ALLOWED_ORIGINS: "https://example.test",
      KNOWLEDGE_DEBUG_API: "true",
      MODEL_API_KEY: "",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: ""
    },
    stdio: ["ignore", "ignore", "pipe"]
  });
  child.stderr.on("data", (chunk) => { stderrRef.value += chunk.toString(); });

  try {
    await waitForHealth(baseUrl, child, stderrRef);
    const response = await fetch(`${baseUrl}/api/knowledge/search?q=stack`);
    assert.equal(
      response.status,
      404,
      "production must not expose the knowledge debug endpoint when KNOWLEDGE_DEBUG_API=true"
    );
    assert.deepEqual(await response.json(), { error: "not found" });
    console.log("node-production-debug-knowledge-ok endpoint=disabled");
  } finally {
    await stop(child);
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
