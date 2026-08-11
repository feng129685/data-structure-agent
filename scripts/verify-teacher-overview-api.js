const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { waitForCapturedCode } = require("./verification-code-fixture");

const root = path.join(__dirname, "..");
const nodeRoot = path.join(root, "backend", "node");
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-teacher-overview-"));
const dbPath = path.join(tmpDir, "test.db");
const verificationCodeFile = path.join(tmpDir, "verification-codes.jsonl");
const port = 19971 + Math.floor(Math.random() * 1000);
const baseUrl = `http://127.0.0.1:${port}`;
const teacherEmail = "teacher@example.com";

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

async function jsonFetch(url, options = {}, expectOk = true) {
  const res = await fetch(url, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {})
    }
  });
  const body = await res.json().catch(() => ({}));
  if (expectOk && !res.ok) throw new Error(`${options.method || "GET"} ${url} failed ${res.status}: ${body.error || res.statusText}`);
  return { res, body };
}

async function register(email) {
  await jsonFetch(`${baseUrl}/api/auth/request-code`, {
    method: "POST",
    body: JSON.stringify({ email, purpose: "register" })
  });
  const { code } = await waitForCapturedCode(verificationCodeFile, email);
  const { body } = await jsonFetch(`${baseUrl}/api/auth/register`, {
    method: "POST",
    body: JSON.stringify({ email, password: "test123456", code })
  });
  if (!body.ok || !body.token) throw new Error(`register failed for ${email}`);
  return body.token;
}

async function saveSnapshot(token, scenario, topic, percent) {
  await jsonFetch(`${baseUrl}/api/learning-snapshot`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      learningProgress: {
        [scenario]: { steps: { read: true, ask: true, animate: true, code: percent > 50, quiz: false }, updatedAt: new Date().toISOString() }
      },
      weakMemory: [
        { id: `${scenario}:${topic}`, scenario, topic, reason: "teacher overview test", source: "test", count: 2, updatedAt: new Date().toISOString() }
      ],
      report: {
        averagePercent: percent,
        activeChapterCount: 1,
        weakCount: 1,
        totalMessages: 5,
        nextScenario: scenario,
        focusTopic: topic,
        statusText: "test status",
        nextAdvice: "test advice",
        recentText: "test recent"
      },
      stats: { activeView: "profile", currentScenario: scenario, selectedChapter: scenario, savedAt: new Date().toISOString() }
    })
  });
}

(async () => {
  const child = spawn(process.execPath, ["server.js"], {
    cwd: nodeRoot,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: dbPath,
      JWT_SECRET: "teacher-overview-test-secret-teacher-overview-test",
      MIMO_API_KEY: process.env.MIMO_API_KEY || "test-key",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: "",
      TEACHER_EMAILS: teacherEmail,
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
    const teacherToken = await register(teacherEmail);
    const studentToken = await register(`student-${Date.now()}@example.com`);
    await saveSnapshot(studentToken, "list", "链表指针顺序", 60);

    const denied = await jsonFetch(`${baseUrl}/api/teacher/overview`, {
      headers: { Authorization: `Bearer ${studentToken}` }
    }, false);
    if (denied.res.status !== 403) throw new Error(`student should receive 403, got ${denied.res.status}`);

    const { body } = await jsonFetch(`${baseUrl}/api/teacher/overview`, {
      headers: { Authorization: `Bearer ${teacherToken}` }
    });
    if (!body.ok || !body.teacher?.isTeacher) throw new Error("teacher overview did not identify teacher");
    if (body.overview.totals.activeSnapshots < 1) throw new Error("teacher overview did not count active snapshots");
    if (!body.overview.weakPoints.some((item) => item.topic === "链表指针顺序")) throw new Error("teacher overview missing weak point aggregation");
    console.log("teacher-overview-api-ok");
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
