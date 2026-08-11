const assert = require("node:assert/strict");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { waitForCapturedCode } = require("./verification-code-fixture");

const root = path.join(__dirname, "..");
const nodeRoot = path.join(root, "backend", "node");

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function getFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close((error) => (error ? reject(error) : resolve(port)));
    });
  });
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
    await sleep(100);
  }
  throw new Error(`server health timeout\\n${stderrRef.value}`);
}

async function request(baseUrl, method, pathname, body, token, ip) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    method,
    headers: {
      ...(body ? { "content-type": "application/json" } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(ip ? { "x-forwarded-for": ip } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  return { response, body: await response.json().catch(() => ({})) };
}

async function register(baseUrl, verificationCodeFile, email, ip) {
  const requested = await request(baseUrl, "POST", "/api/auth/request-code", {
    email,
    purpose: "register"
  }, null, ip);
  assert.equal(requested.response.status, 200, JSON.stringify(requested.body));
  const { code } = await waitForCapturedCode(verificationCodeFile, email);
  const registered = await request(baseUrl, "POST", "/api/auth/register", {
    email,
    code,
    password: "test-password-123"
  }, null, ip);
  assert.equal(registered.response.status, 200, JSON.stringify(registered.body));
  assert.ok(registered.body.token);
  return registered.body.token;
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-assignment-owner-"));
  const port = await getFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const verificationCodeFile = path.join(tempDir, "verification-codes.jsonl");
  const stderrRef = { value: "" };
  const child = spawn(process.execPath, ["server.js"], {
    cwd: nodeRoot,
    env: {
      ...process.env,
      NODE_ENV: "test",
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: path.join(tempDir, "test.db"),
      NODE_STATE_DIR: path.join(tempDir, "state"),
      VERIFICATION_CODE_FILE: verificationCodeFile,
      NODE_COMPAT_JWT_SECRET: "teacher-assignment-ownership-test-secret",
      TEACHER_EMAILS: "owner-teacher@example.com,other-teacher@example.com",
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
    const ownerToken = await register(baseUrl, verificationCodeFile, "owner-teacher@example.com", "10.0.0.1");
    const otherTeacherToken = await register(baseUrl, verificationCodeFile, "other-teacher@example.com", "10.0.0.2");
    const studentToken = await register(baseUrl, verificationCodeFile, "student@example.com", "10.0.0.3");

    const updatedTitle = "Owner update remains available";
    const updateTarget = await request(baseUrl, "POST", "/api/teacher/assignments", {
      id: "owner-update-target",
      scenario: "stack",
      topic: "stack",
      title: "Original owner assignment",
      description: "Created by the owning teacher"
    }, ownerToken);
    assert.equal(updateTarget.response.status, 200, JSON.stringify(updateTarget.body));

    const ownerUpdate = await request(baseUrl, "POST", "/api/teacher/assignments", {
      id: "owner-update-target",
      scenario: "stack",
      topic: "stack",
      title: updatedTitle,
      description: "Updated by the owning teacher"
    }, ownerToken);
    assert.equal(ownerUpdate.response.status, 200, JSON.stringify(ownerUpdate.body));
    assert.equal(ownerUpdate.body.assignment?.title, updatedTitle);

    const foreignUpdate = await request(baseUrl, "POST", "/api/teacher/assignments", {
      id: "owner-update-target",
      scenario: "queue",
      topic: "queue",
      title: "Foreign teacher must not replace this",
      description: "This request must be rejected"
    }, otherTeacherToken);

    const archiveTarget = await request(baseUrl, "POST", "/api/teacher/assignments", {
      id: "owner-archive-target",
      scenario: "queue",
      topic: "queue",
      title: "Only the owner may archive this",
      description: "Archive ownership test"
    }, ownerToken);
    assert.equal(archiveTarget.response.status, 200, JSON.stringify(archiveTarget.body));

    const foreignArchive = await request(
      baseUrl,
      "DELETE",
      "/api/teacher/assignments/owner-archive-target",
      null,
      otherTeacherToken
    );

    const assignmentsAfterForeignRequests = await request(baseUrl, "GET", "/api/assignments", null, studentToken);
    assert.equal(assignmentsAfterForeignRequests.response.status, 200, JSON.stringify(assignmentsAfterForeignRequests.body));

    assert.deepEqual({
      foreignUpdateStatus: foreignUpdate.response.status,
      foreignArchiveStatus: foreignArchive.response.status,
      updateTargetTitle: assignmentsAfterForeignRequests.body.assignments?.find((assignment) => assignment.id === "owner-update-target")?.title,
      archiveTargetVisible: assignmentsAfterForeignRequests.body.assignments?.some((assignment) => assignment.id === "owner-archive-target")
    }, {
      foreignUpdateStatus: 403,
      foreignArchiveStatus: 403,
      updateTargetTitle: updatedTitle,
      archiveTargetVisible: true
    });

    const ownerArchive = await request(
      baseUrl,
      "DELETE",
      "/api/teacher/assignments/owner-archive-target",
      null,
      ownerToken
    );
    assert.equal(ownerArchive.response.status, 200, JSON.stringify(ownerArchive.body));

    const assignmentsAfterOwnerArchive = await request(baseUrl, "GET", "/api/assignments", null, studentToken);
    assert.equal(assignmentsAfterOwnerArchive.response.status, 200, JSON.stringify(assignmentsAfterOwnerArchive.body));
    assert.ok(!assignmentsAfterOwnerArchive.body.assignments?.some((assignment) => assignment.id === "owner-archive-target"));

    console.log("teacher-assignment-ownership-ok");
  } finally {
    if (child.exitCode === null) {
      await new Promise((resolve) => {
        child.once("exit", resolve);
        child.kill();
      });
    }
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
