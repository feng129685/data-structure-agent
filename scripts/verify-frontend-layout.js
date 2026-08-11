const assert = require("node:assert/strict");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const root = path.join(__dirname, "..");
const backendRoot = path.join(root, "backend", "node");
const frontendPath = path.join(root, "frontend", "index.html");
const legacyPrototypePath = path.join(root, "frontend", "prototype.html");

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

async function waitForHealth(baseUrl, child, readStderr) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      const detail = typeof readStderr === "function" ? readStderr().trim() : "";
      throw new Error(`server exited early with code ${child.exitCode}${detail ? `: ${detail}` : ""}`);
    }
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("server did not become healthy in time");
}

async function stopChild(child) {
  if (child.exitCode !== null) return;
  child.kill();
  await Promise.race([
    new Promise((resolve) => child.once("exit", resolve)),
    new Promise((resolve) => setTimeout(resolve, 1_500))
  ]);
}

async function main() {
  assert.ok(fs.existsSync(frontendPath), "canonical frontend/index.html is required");
  assert.ok(fs.existsSync(legacyPrototypePath), "legacy frontend/prototype.html must be preserved during migration");
  const frontend = fs.readFileSync(frontendPath, "utf8");
  const legacyPrototype = fs.readFileSync(legacyPrototypePath, "utf8");
  assert.match(frontend, /data-core-module="presentation"/, "canonical frontend must contain the PPT module");
  assert.match(frontend, /id="classroomView"/, "canonical frontend must contain the classroom module");

  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-frontend-layout-"));
  const port = await getFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const child = spawn(process.execPath, ["server.js"], {
    cwd: backendRoot,
    env: {
      ...process.env,
      NODE_ENV: "test",
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: path.join(tempDir, "test.db"),
      KNOWLEDGE_DIR: path.join(tempDir, "knowledge"),
      PRESENTATION_DIR: path.join(tempDir, "presentation-materials"),
      FRONTEND_DIR: path.join(root, "frontend"),
      JWT_SECRET: "frontend-layout-test-secret-32-characters",
      MODEL_API_KEY: "",
      MIMO_API_KEY: "",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: ""
    },
    stdio: ["ignore", "ignore", "pipe"]
  });
  let stderr = "";
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });

  try {
    await waitForHealth(baseUrl, child, () => stderr);
    for (const pathname of ["/", "/index.html"]) {
      const response = await fetch(`${baseUrl}${pathname}`);
      assert.equal(response.status, 200, `${pathname} must resolve to the canonical frontend`);
      assert.match(response.headers.get("content-type") || "", /^text\/html/);
      assert.equal(await response.text(), frontend, `${pathname} must not serve a divergent frontend copy`);
    }
    const headResponse = await fetch(`${baseUrl}/`, { method: "HEAD" });
    assert.equal(headResponse.status, 200, "HEAD / must provide a lightweight public entry health check");
    assert.match(headResponse.headers.get("content-type") || "", /^text\/html/);
    assert.equal((await headResponse.arrayBuffer()).byteLength, 0, "HEAD / must not transfer the frontend body");
    const prototypeResponse = await fetch(`${baseUrl}/prototype.html`);
    assert.equal(prototypeResponse.status, 200, "/prototype.html must preserve the legacy entry during migration");
    assert.equal(await prototypeResponse.text(), legacyPrototype, "/prototype.html must serve frontend/prototype.html");
  } finally {
    await stopChild(child);
    fs.rmSync(tempDir, { recursive: true, force: true });
  }

  if (stderr.trim()) process.stderr.write(stderr);
  console.log("frontend-layout-ok canonical=frontend/index.html legacy=frontend/prototype.html compatibilityEntries=3");
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
