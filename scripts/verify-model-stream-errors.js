const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const root = path.join(__dirname, "..");

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

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

async function readBody(req) {
  let raw = "";
  for await (const chunk of req) raw += chunk;
  return raw;
}

async function waitForHealth(baseUrl, child, stderrRef) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`server exited early\n${stderrRef.value}`);
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch {}
    await sleep(100);
  }
  throw new Error(`server health timeout\n${stderrRef.value}`);
}

async function postChat(baseUrl, prompt, timeoutMs = 2_000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(`${baseUrl}/api/chat`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        prompt,
        scenario: { chapter: "test", title: "test", lead: "", summary: [], references: [] },
        mode: { id: "concept", label: "concept" },
        history: [],
        stream: true
      }),
      signal: controller.signal
    });
  } finally {
    clearTimeout(timer);
  }
}

async function main() {
  const appPort = await getFreePort();
  const modelPort = await getFreePort();
  const appBaseUrl = `http://127.0.0.1:${appPort}`;
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-agent-model-stream-"));
  const stderrRef = { value: "" };

  const modelServer = http.createServer(async (req, res) => {
    const raw = await readBody(req);
    if (raw.includes("STREAM_FAIL")) {
      res.writeHead(429, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: { message: "mock rate limit" } }));
      return;
    }
    if (raw.includes("STREAM_TIMEOUT")) return;
    if (raw.includes("STREAM_STALL")) {
      res.writeHead(200, { "content-type": "text/event-stream" });
      res.write(": connected\n\n");
      return;
    }
    res.writeHead(200, { "content-type": "text/event-stream" });
    res.end('data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n');
  });
  await new Promise((resolve) => modelServer.listen(modelPort, "127.0.0.1", resolve));

  const child = spawn(process.execPath, ["server.js"], {
    cwd: root,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(appPort),
      DB_PATH: path.join(tempDir, "test.db"),
      MODEL_API_KEY: "fixture-key",
      MODEL_BASE_URL: `http://127.0.0.1:${modelPort}`,
      MODEL_NAME: "fixture-model",
      MODEL_TIMEOUT_MS: "300",
      MODEL_STREAM_IDLE_TIMEOUT_MS: "300",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: ""
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  child.stderr.on("data", (chunk) => { stderrRef.value += chunk.toString(); });

  try {
    await waitForHealth(appBaseUrl, child, stderrRef);

    const failed = await postChat(appBaseUrl, "STREAM_FAIL");
    assert.equal(failed.status, 429);
    assert.match(failed.headers.get("content-type") || "", /application\/json/);
    assert.match((await failed.json()).error || "", /mock rate limit/);

    const startedAt = Date.now();
    const timedOut = await postChat(appBaseUrl, "STREAM_TIMEOUT");
    assert.equal(timedOut.status, 504);
    assert.ok(Date.now() - startedAt < 1_500, "model timeout response was too slow");

    const stalled = await postChat(appBaseUrl, "STREAM_STALL");
    assert.equal(stalled.status, 200);
    const stalledText = await Promise.race([
      stalled.text(),
      sleep(1_500).then(() => { throw new Error("stalled model stream was not terminated"); })
    ]);
    assert.match(stalledText, /模型流读取超时/);

    const success = await postChat(appBaseUrl, "STREAM_OK");
    assert.equal(success.status, 200);
    assert.match(success.headers.get("content-type") || "", /text\/event-stream/);
    assert.match(await success.text(), /"content":"ok"/);

    console.log("model-stream-errors-ok status=429 timeout=504 stall=handled success=200");
  } finally {
    child.kill();
    await new Promise((resolve) => {
      if (child.exitCode !== null) return resolve();
      child.once("exit", resolve);
      setTimeout(resolve, 1500);
    });
    await new Promise((resolve) => modelServer.close(resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
