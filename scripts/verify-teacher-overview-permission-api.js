const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const root = path.join(__dirname, "..");
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-teacher-permission-"));
const dbPath = path.join(tmpDir, "test.db");
const port = 20971 + Math.floor(Math.random() * 1000);
const baseUrl = `http://127.0.0.1:${port}`;
const email = "first-user@example.com";

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

async function waitForDevCode(stdoutRef, targetEmail) {
  const deadline = Date.now() + 8_000;
  const pattern = new RegExp(`for ${targetEmail.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}: (\\d{6})`);
  while (Date.now() < deadline) {
    const match = stdoutRef.value.match(pattern);
    if (match) return match[1];
    await wait(150);
  }
  throw new Error(`verification code for ${targetEmail} was not logged`);
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
  return { res, body };
}

async function register(stdoutRef) {
  const request = await jsonFetch(`${baseUrl}/api/auth/request-code`, {
    method: "POST",
    body: JSON.stringify({ email, purpose: "register" })
  });
  if (!request.res.ok) throw new Error(`request code failed: ${request.body.error || request.res.status}`);
  const code = await waitForDevCode(stdoutRef, email);
  const registered = await jsonFetch(`${baseUrl}/api/auth/register`, {
    method: "POST",
    body: JSON.stringify({ email, password: "test123456", code })
  });
  if (!registered.res.ok || !registered.body.token) throw new Error(`register failed: ${registered.body.error || registered.res.status}`);
  return registered.body.token;
}

(async () => {
  const child = spawn(process.execPath, ["server.js"], {
    cwd: root,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: dbPath,
      JWT_SECRET: "teacher-permission-test-secret-teacher-permission",
      MIMO_API_KEY: process.env.MIMO_API_KEY || "test-key",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: "",
      TEACHER_EMAILS: "",
      ADMIN_EMAILS: "",
      ALLOW_FIRST_USER_TEACHER: ""
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  const stdoutRef = { value: "" };
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdoutRef.value += chunk.toString(); });
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });

  try {
    await waitForServer(child);
    const token = await register(stdoutRef);
    const me = await jsonFetch(`${baseUrl}/api/auth/me`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!me.res.ok) throw new Error(`auth me failed: ${me.body.error || me.res.status}`);
    if (me.body.user?.isTeacher) throw new Error("first registered user should not be a teacher by default");

    const overview = await jsonFetch(`${baseUrl}/api/teacher/overview`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (overview.res.status !== 403) throw new Error(`teacher overview should deny default first user, got ${overview.res.status}`);
    console.log("teacher-overview-permission-api-ok");
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
