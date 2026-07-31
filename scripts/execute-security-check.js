const assert = require("node:assert/strict");
const http = require("node:http");
const { spawn } = require("node:child_process");

const APP_PORT = 8891;
const MOCK_PORT = 18891;
const APP_BASE = `http://127.0.0.1:${APP_PORT}`;
const MOCK_BASE = `http://127.0.0.1:${MOCK_PORT}`;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.on("data", (chunk) => {
      raw += chunk;
    });
    req.on("end", () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

async function startMockExecutor() {
  const server = http.createServer(async (req, res) => {
    if (req.method !== "POST") {
      res.writeHead(404);
      res.end("not found");
      return;
    }

    const body = await readJson(req).catch(() => ({}));
    const code = String(body.source_code || body.files?.[0]?.content || "");

    if (req.url.startsWith("/submissions")) {
      if (code.includes("JUDGE_HANG")) {
        return;
      }
      if (code.includes("HANG")) {
        return;
      }
      if (code.includes("HOLD")) {
        await sleep(600);
      }
      const stdout = code.includes("PRINT_BIG") ? "X".repeat(500) : "ok\n";
      const payload = {
        stdout,
        stderr: "",
        compile_output: "",
        status: { id: 3, description: "Accepted" }
      };
      res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      res.end(JSON.stringify(payload));
      return;
    }

    if (req.url === "/execute") {
      if (code.includes("HANG") && !code.includes("JUDGE_HANG")) {
        return;
      }
      if (code.includes("HOLD")) {
        await sleep(600);
      }
      const stdout = code.includes("PRINT_BIG") ? "X".repeat(500) : "ok\n";
      const payload = {
        run: { stdout, stderr: "" },
        compile: { stderr: "" }
      };
      res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      res.end(JSON.stringify(payload));
      return;
    }

    res.writeHead(404);
    res.end("not found");
  });

  await new Promise((resolve) => server.listen(MOCK_PORT, "127.0.0.1", resolve));
  return server;
}

async function fetchJson(path, body, extraHeaders = {}, timeoutMs = 5000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${APP_BASE}${path}`, {
      method: "POST",
      headers: {
        "content-type": "application/json; charset=utf-8",
        ...extraHeaders
      },
      body: JSON.stringify(body),
      signal: controller.signal
    });
    const json = await response.json();
    return { status: response.status, json };
  } finally {
    clearTimeout(timer);
  }
}

async function waitForHealth(child, timeoutMs = 10000) {
  const startedAt = Date.now();
  let stderr = "";
  if (child.stderr) {
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });
  }

  while (Date.now() - startedAt < timeoutMs) {
    if (child.exitCode !== null) {
      throw new Error(`app server exited early: ${child.exitCode}\n${stderr}`);
    }
    try {
      const response = await fetch(`${APP_BASE}/healthz`);
      if (response.ok) return;
    } catch {}
    await sleep(150);
  }
  throw new Error(`health check timeout\n${stderr}`);
}

async function run() {
  const mock = await startMockExecutor();
  const child = spawn(process.execPath, ["server.js"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(APP_PORT),
      JUDGE0_BASE_URL: MOCK_BASE,
      PISTON_BASE_URL: MOCK_BASE,
      EXECUTE_RATE_WINDOW_MS: "60000",
      EXECUTE_RATE_MAX: "2",
      EXECUTE_MAX_CONCURRENCY: "1",
      EXECUTE_PER_IP_CONCURRENCY: "1",
      EXECUTE_TIMEOUT_MS: "2000",
      EXECUTE_OUTPUT_MAX_CHARS: "120",
      EXECUTE_ERROR_MAX_CHARS: "120"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  const cleanup = async () => {
    child.kill("SIGTERM");
    await sleep(300);
    if (child.exitCode === null) child.kill("SIGKILL");
    await new Promise((resolve) => mock.close(resolve));
  };

  let failures = 0;
  try {
    await waitForHealth(child);

    const cases = [
      ["rate limit on execute", async () => {
        const headers = { "x-forwarded-for": "10.0.0.11" };
        const payload = { language: "c", code: "int main(void){return 0;}" };
        const first = await fetchJson("/api/execute", payload, headers);
        const second = await fetchJson("/api/execute", payload, headers);
        const third = await fetchJson("/api/execute", payload, headers);
        assert.equal(first.status, 200);
        assert.equal(second.status, 200);
        assert.equal(third.status, 429);
      }],
      ["per-ip concurrency limit", async () => {
        const headers = { "x-forwarded-for": "10.0.0.12" };
        const payload = { language: "c", code: "// HOLD\nint main(void){return 0;}" };
        const firstPromise = fetchJson("/api/execute", payload, headers, 7000);
        await sleep(150);
        const second = await fetchJson("/api/execute", payload, headers, 7000);
        const first = await firstPromise;
        assert.equal(first.status, 200);
        assert.ok(second.status === 429 || second.status === 503, `expected 429/503, got ${second.status}`);
      }],
      ["execution timeout guard", async () => {
        const headers = { "x-forwarded-for": "10.0.0.13" };
        const payload = { language: "c", code: "// HANG\nint main(void){return 0;}" };
        const startedAt = Date.now();
        const result = await fetchJson("/api/execute", payload, headers, 4500);
        const elapsed = Date.now() - startedAt;
        assert.equal(result.status, 504);
        assert.ok(elapsed < 4000, `timeout response too slow: ${elapsed}ms`);
      }],
      ["fallback after primary executor timeout", async () => {
        const headers = { "x-forwarded-for": "10.0.0.15" };
        const payload = { language: "c", code: "// JUDGE_HANG\nint main(void){return 0;}" };
        const result = await fetchJson("/api/execute", payload, headers, 4500);
        assert.equal(result.status, 200);
        assert.equal(result.json.provider, "piston");
        assert.match(result.json.warning || "", /Judge0/);
      }],
      ["output truncation", async () => {
        const headers = { "x-forwarded-for": "10.0.0.14" };
        const payload = { language: "c", code: "// PRINT_BIG\nint main(void){return 0;}" };
        const result = await fetchJson("/api/execute", payload, headers);
        assert.equal(result.status, 200);
        assert.ok(result.json.output.includes("[输出已截断"), "missing truncation marker");
        assert.ok(result.json.output.length <= 160, `output too long: ${result.json.output.length}`);
      }]
    ];

    for (const [name, fn] of cases) {
      try {
        await fn();
        console.log(`PASS ${name}`);
      } catch (error) {
        failures++;
        console.error(`FAIL ${name}: ${error.message}`);
      }
    }
  } finally {
    await cleanup();
  }

  process.exit(failures === 0 ? 0 : 1);
}

run().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
