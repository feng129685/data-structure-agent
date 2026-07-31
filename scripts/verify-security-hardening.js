const assert = require("node:assert/strict");
const fs = require("node:fs");
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

async function waitForHealth(baseUrl, child, stderrRef) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`server exited early (${child.exitCode})\n${stderrRef.value}`);
    }
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch {}
    await sleep(120);
  }
  throw new Error(`server health timeout\n${stderrRef.value}`);
}

async function jsonFetch(baseUrl, pathname, body, headers = {}) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body)
  });
  return { response, body: await response.json().catch(() => ({})) };
}

async function waitForCode(stdoutRef, email, startAt) {
  const escaped = email.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const pattern = new RegExp(`verification code for ${escaped}: (\\d{6})`, "i");
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const match = stdoutRef.value.slice(startAt).match(pattern);
    if (match) return match[1];
    await sleep(50);
  }
  throw new Error(`verification code was not logged for ${email}`);
}

async function register(baseUrl, stdoutRef, email, ip) {
  const startAt = stdoutRef.value.length;
  const requested = await jsonFetch(baseUrl, "/api/auth/request-code", {
    email,
    purpose: "register"
  }, { "x-forwarded-for": ip });
  assert.equal(requested.response.status, 200);
  const code = await waitForCode(stdoutRef, email, startAt);
  const registered = await jsonFetch(baseUrl, "/api/auth/register", {
    email,
    code,
    password: "test-password-123"
  }, { "x-forwarded-for": ip });
  assert.equal(registered.response.status, 200, JSON.stringify(registered.body));
  assert.ok(registered.body.token);
  return registered.body.token;
}

async function uploadPdf(baseUrl, token, content, filename, ip) {
  const form = new FormData();
  form.append("file", new Blob([content], { type: "application/pdf" }), filename);
  const response = await fetch(`${baseUrl}/api/upload-pdf`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "x-forwarded-for": ip
    },
    body: form
  });
  return { response, body: await response.json().catch(() => ({})) };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-agent-security-"));
  const port = await getFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const stdoutRef = { value: "" };
  const stderrRef = { value: "" };
  const child = spawn(process.execPath, ["server.js"], {
    cwd: root,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: path.join(tempDir, "test.db"),
      PDF_DIR: path.join(tempDir, "pdfs"),
      MODEL_API_KEY: "fixture-key",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: "",
      TEACHER_EMAILS: "teacher@example.com",
      CODE_REQUEST_RATE_MAX: "2",
      CODE_REQUEST_IP_RATE_MAX: "20",
      CODE_REQUEST_RATE_WINDOW_MS: "60000",
      CODE_MAX_ATTEMPTS: "3",
      PDF_UPLOAD_MAX_BYTES: "512"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  child.stdout.on("data", (chunk) => { stdoutRef.value += chunk.toString(); });
  child.stderr.on("data", (chunk) => { stderrRef.value += chunk.toString(); });

  try {
    await waitForHealth(baseUrl, child, stderrRef);

    const rootResponse = await fetch(`${baseUrl}/`);
    const html = await rootResponse.text();
    assert.match(rootResponse.headers.get("content-security-policy") || "", /script-src-attr 'none'/);
    assert.equal(rootResponse.headers.get("x-content-type-options"), "nosniff");
    assert.match(html, /\/vendor\/dompurify\.min\.js/);
    assert.match(html, /DOMPurify\.sanitize/);
    assert.doesNotMatch(html, /\son[a-z]+\s*=/i, "CSP-blocked inline event handlers should not remain");

    const healthPayload = await (await fetch(`${baseUrl}/healthz`)).json();
    assert.equal(Object.hasOwn(healthPayload, "modelBaseUrl"), false);
    assert.equal(Object.hasOwn(healthPayload, "smtpHost"), false);
    assert.equal(Object.hasOwn(healthPayload, "smtpFrom"), false);

    const purifyResponse = await fetch(`${baseUrl}/vendor/dompurify.min.js`);
    assert.equal(purifyResponse.status, 200);
    assert.match(purifyResponse.headers.get("content-type") || "", /javascript/);

    const teacherToken = await register(baseUrl, stdoutRef, "teacher@example.com", "10.0.0.1");
    const studentToken = await register(baseUrl, stdoutRef, "student@example.com", "10.0.0.2");

    const forbidden = await uploadPdf(baseUrl, studentToken, "%PDF-1.4\nstudent", "student.pdf", "10.0.0.2");
    assert.equal(forbidden.response.status, 403);

    const fakePdf = await uploadPdf(baseUrl, teacherToken, "not a pdf", "fake.pdf", "10.0.0.1");
    assert.equal(fakePdf.response.status, 415);

    const oversized = await uploadPdf(
      baseUrl,
      teacherToken,
      `%PDF-1.4\n${"X".repeat(2048)}`,
      "large.pdf",
      "10.0.0.1"
    );
    assert.equal(oversized.response.status, 413);

    const rateEmail = "rate-limit@example.com";
    const statuses = [];
    for (let index = 0; index < 3; index += 1) {
      const result = await jsonFetch(baseUrl, "/api/auth/request-code", {
        email: rateEmail,
        purpose: "register"
      }, { "x-forwarded-for": "10.0.0.3" });
      statuses.push(result.response.status);
    }
    assert.deepEqual(statuses, [200, 200, 429]);

    const attemptEmail = "attempt-limit@example.com";
    const codeStart = stdoutRef.value.length;
    const attemptRequested = await jsonFetch(baseUrl, "/api/auth/request-code", {
      email: attemptEmail,
      purpose: "register"
    }, { "x-forwarded-for": "10.0.0.4" });
    assert.equal(attemptRequested.response.status, 200);
    const validCode = await waitForCode(stdoutRef, attemptEmail, codeStart);
    for (let index = 0; index < 3; index += 1) {
      const wrong = await jsonFetch(baseUrl, "/api/auth/register", {
        email: attemptEmail,
        code: "000000",
        password: "test-password-123"
      }, { "x-forwarded-for": "10.0.0.4" });
      assert.equal(wrong.response.status, 401);
    }
    const locked = await jsonFetch(baseUrl, "/api/auth/register", {
      email: attemptEmail,
      code: validCode,
      password: "test-password-123"
    }, { "x-forwarded-for": "10.0.0.4" });
    assert.equal(locked.response.status, 401);

    console.log("security-hardening-ok headers=4 uploads=3 auth-rate=1 code-attempts=1");
  } finally {
    child.kill();
    await new Promise((resolve) => {
      if (child.exitCode !== null) return resolve();
      child.once("exit", resolve);
      setTimeout(resolve, 1500);
    });
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
