const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { waitForCapturedCode } = require("./verification-code-fixture");

const root = path.join(__dirname, "..");
const nodeRoot = path.join(root, "backend", "node");
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-learning-snapshot-"));
const dbPath = path.join(tmpDir, "test.db");
const verificationCodeFile = path.join(tmpDir, "verification-codes.jsonl");
const port = 18971 + Math.floor(Math.random() * 1000);
const baseUrl = `http://127.0.0.1:${port}`;

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForServer(child) {
  const deadline = Date.now() + 12_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`server exited early with code ${child.exitCode}`);
    try {
      const res = await fetch(`${baseUrl}/healthz`);
      if (res.ok) return;
    } catch {}
    await wait(250);
  }
  throw new Error("server did not become ready");
}

async function jsonFetch(url, options = {}) {
  const res = await fetch(url, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {})
    }
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${options.method || "GET"} ${url} failed ${res.status}: ${body.error || res.statusText}`);
  return body;
}

(async () => {
  const child = spawn(process.execPath, ["server.js"], {
    cwd: nodeRoot,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: dbPath,
      JWT_SECRET: "learning-snapshot-test-secret-learning-snapshot-test",
      MIMO_API_KEY: process.env.MIMO_API_KEY || "test-key",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: "",
      NODE_ENV: "test",
      VERIFICATION_CODE_FILE: verificationCodeFile
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  const stdoutRef = { value: "" };
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdoutRef.value += chunk.toString(); });
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });

  try {
    await waitForServer(child);
    const email = `snapshot-${Date.now()}@example.com`;
    const password = "test123456";
    await jsonFetch(`${baseUrl}/api/auth/request-code`, {
      method: "POST",
      body: JSON.stringify({ email, purpose: "register" })
    });
    const { code } = await waitForCapturedCode(verificationCodeFile, email);
    const reg = await jsonFetch(`${baseUrl}/api/auth/register`, {
      method: "POST",
      body: JSON.stringify({ email, password, code })
    });
    if (!reg.ok || !reg.token) throw new Error("register did not return token");
    const auth = { Authorization: `Bearer ${reg.token}` };

    const before = await jsonFetch(`${baseUrl}/api/learning-snapshot`, { headers: auth });
    if (before.snapshot !== null) throw new Error("new user should not have a snapshot yet");

    const payload = {
      learningProgress: {
        stack: { steps: { read: true, ask: true, animate: false, code: false, quiz: false }, updatedAt: new Date().toISOString() }
      },
      weakMemory: [
        { id: "stack:栈顶", scenario: "stack", topic: "栈顶", reason: "测试薄弱点", source: "测试", count: 2, updatedAt: new Date().toISOString() }
      ],
      report: {
        averagePercent: 25,
        activeChapterCount: 1,
        weakCount: 1,
        totalMessages: 3,
        nextScenario: "stack",
        focusTopic: "栈顶",
        statusText: "测试状态",
        nextAdvice: "测试建议",
        recentText: "测试最近状态"
      },
      stats: { activeView: "profile", currentScenario: "stack", selectedChapter: "栈与队列", savedAt: new Date().toISOString() }
    };

    const saved = await jsonFetch(`${baseUrl}/api/learning-snapshot`, {
      method: "PUT",
      headers: auth,
      body: JSON.stringify(payload)
    });
    if (!saved.ok || saved.snapshot.report.averagePercent !== 25) throw new Error("snapshot save did not echo report");

    const loaded = await jsonFetch(`${baseUrl}/api/learning-snapshot`, { headers: auth });
    if (!loaded.snapshot || loaded.snapshot.weakMemory[0].topic !== "栈顶") throw new Error("snapshot load did not return saved weak memory");

    await jsonFetch(`${baseUrl}/api/learning-snapshot`, { method: "DELETE", headers: auth });
    const afterDelete = await jsonFetch(`${baseUrl}/api/learning-snapshot`, { headers: auth });
    if (afterDelete.snapshot !== null) throw new Error("snapshot delete did not clear row");

    console.log("learning-snapshot-api-ok");
  } finally {
    child.kill();
    await wait(250);
    fs.rmSync(tmpDir, { recursive: true, force: true });
    if (stderr && child.exitCode !== null && child.exitCode !== 0) process.stderr.write(stderr);
  }
})().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
