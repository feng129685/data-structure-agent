const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");

const root = path.join(__dirname, "..");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-targeted-assignment-"));
const dbPath = path.join(tempDir, "data.db");
const port = String(19000 + Math.floor(Math.random() * 20000));
const env = {
  ...process.env,
  PORT: port,
  DB_PATH: dbPath,
  JWT_SECRET: "targeted-assignment-test-secret",
  TEACHER_EMAILS: "teacher@example.com",
  SMTP_HOST: "",
  MIMO_API_KEY: "",
  DATA_DIR: tempDir
};

const script = String.raw`
const http = require("http");
const assert = require("assert");
const crypto = require("crypto");
const Database = require("better-sqlite3");
require("./server.js");

function request(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const payload = body ? JSON.stringify(body) : "";
    const req = http.request({
      hostname: "127.0.0.1",
      port: process.env.PORT,
      method,
      path,
      headers: {
        "content-type": "application/json",
        "content-length": Buffer.byteLength(payload),
        ...(token ? { authorization: "Bearer " + token } : {})
      }
    }, (res) => {
      let data = "";
      res.on("data", (chunk) => data += chunk);
      res.on("end", () => {
        try { resolve({ status: res.statusCode, body: data ? JSON.parse(data) : {} }); }
        catch (error) { reject(error); }
      });
    });
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function waitForServer() {
  for (let i = 0; i < 30; i += 1) {
    try {
      const res = await request("GET", "/healthz");
      if (res.status === 200) return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("server did not start");
}

function signToken(userId, email) {
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify({ userId, email, iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600 })).toString("base64url");
  const sig = crypto.createHmac("sha256", process.env.JWT_SECRET).update(header + "." + payload).digest("base64url");
  return header + "." + payload + "." + sig;
}

function createUser(email) {
  const db = new Database(process.env.DB_PATH);
  const info = db.prepare("INSERT INTO users (email, created_at) VALUES (?, datetime('now'))").run(email);
  const user = db.prepare("SELECT id, email, created_at FROM users WHERE id = ?").get(info.lastInsertRowid);
  db.close();
  return { user, token: signToken(user.id, user.email) };
}

(async () => {
  await waitForServer();
  const teacher = createUser("teacher@example.com");
  const studentA = createUser("student-a@example.com");
  const studentB = createUser("student-b@example.com");

  const targeted = await request("POST", "/api/teacher/assignments", {
    scenario: "stack",
    topic: "栈",
    title: "需复盘任务：栈",
    description: "只发给 A 和 B",
    targetStudentIds: [studentA.user.id, studentB.user.id],
    steps: [
      { id: "materials", label: "看资料" },
      { id: "animation", label: "看动画" },
      { id: "compiler", label: "跑 C 实验" },
      { id: "coach", label: "伴学复盘" }
    ]
  }, teacher.token);
  assert.equal(targeted.status, 200);
  assert.deepEqual(targeted.body.assignment.targetStudentIds, [studentA.user.id, studentB.user.id]);

  const broadcast = await request("POST", "/api/teacher/assignments", {
    scenario: "queue",
    topic: "队列",
    title: "本周复习任务：队列",
    description: "全班可见"
  }, teacher.token);
  assert.equal(broadcast.status, 200);
  assert.deepEqual(broadcast.body.assignment.targetStudentIds, []);

  const aAssignments = await request("GET", "/api/assignments", null, studentA.token);
  const bAssignments = await request("GET", "/api/assignments", null, studentB.token);
  assert.equal(aAssignments.status, 200);
  assert.equal(bAssignments.status, 200);
  assert.ok(aAssignments.body.assignments.some((item) => item.title === "需复盘任务：栈"));
  assert.ok(aAssignments.body.assignments.some((item) => item.title === "本周复习任务：队列"));
  assert.ok(bAssignments.body.assignments.some((item) => item.title === "需复盘任务：栈"));
  assert.ok(bAssignments.body.assignments.some((item) => item.title === "本周复习任务：队列"));

  const studentC = createUser("student-c@example.com");
  const cAssignments = await request("GET", "/api/assignments", null, studentC.token);
  assert.equal(cAssignments.status, 200);
  assert.ok(!cAssignments.body.assignments.some((item) => item.title === "需复盘任务：栈"));
  assert.ok(cAssignments.body.assignments.some((item) => item.title === "本周复习任务：队列"));

  const snapshot = await request("PUT", "/api/learning-snapshot", {
    teacherTasks: {
      stack: {
        key: "assignment:" + targeted.body.assignment.id,
        assignmentId: targeted.body.assignment.id,
        scenario: "stack",
        topic: "栈",
        steps: { materials: true, animation: false, compiler: false, coach: false },
        updatedAt: new Date().toISOString()
      }
    },
    report: { averagePercent: 25, activeChapterCount: 1, weakCount: 0 }
  }, studentA.token);
  assert.equal(snapshot.status, 200);

  const overview = await request("GET", "/api/teacher/overview", null, teacher.token);
  assert.equal(overview.status, 200);
  const targetedProgress = overview.body.overview.assignmentProgress.find((item) => item.title === "需复盘任务：栈");
  assert.ok(targetedProgress);
  assert.equal(targetedProgress.targeted, true);
  assert.equal(targetedProgress.targetStudentCount, 2);
  assert.equal(targetedProgress.studentStatusSummary.inProgress, 1);
  assert.equal(targetedProgress.studentStatusSummary.notStarted, 1);
  assert.equal(targetedProgress.studentStatus.length, 2);
  assert.ok(targetedProgress.studentStatus.some((item) => item.status === "in-progress" && item.percent === 25));
  assert.ok(targetedProgress.studentStatus.some((item) => item.status === "not-started" && item.percent === 0));

  const rescue = await request("POST", "/api/teacher/assignments", {
    scenario: "stack",
    topic: "栈",
    title: "个人补救任务：栈",
    description: "只发给 A 的短任务",
    targetStudentIds: [studentA.user.id],
    steps: [
      { id: "materials", label: "读1句定义" },
      { id: "animation", label: "看1步变化" },
      { id: "compiler", label: "跑1个例子" },
      { id: "coach", label: "交1句复盘" }
    ]
  }, teacher.token);
  assert.equal(rescue.status, 200);
  assert.deepEqual(rescue.body.assignment.targetStudentIds, [studentA.user.id]);
  const aRescueAssignments = await request("GET", "/api/assignments", null, studentA.token);
  const bRescueAssignments = await request("GET", "/api/assignments", null, studentB.token);
  assert.ok(aRescueAssignments.body.assignments.some((item) => item.title === "个人补救任务：栈"));
  assert.ok(!bRescueAssignments.body.assignments.some((item) => item.title === "个人补救任务：栈"));

  const rescueSnapshot = await request("PUT", "/api/learning-snapshot", {
    teacherTasks: {
      rescue: {
        key: "assignment:" + rescue.body.assignment.id,
        assignmentId: rescue.body.assignment.id,
        scenario: "stack",
        topic: "栈",
        steps: { materials: true, animation: true, compiler: true, coach: true },
        updatedAt: new Date().toISOString()
      }
    },
    report: { averagePercent: 100, activeChapterCount: 1, weakCount: 0 },
    stats: {
      reviewNotes: [{
        scenario: "stack",
        source: "个人补救任务",
        title: "栈 补救闭环",
        detail: "完成老师单独安排的短任务。",
        action: "teacher-rescue-complete",
        tag: "闭环证据",
        evidence: "补救路径：读1句定义 / 看1步变化 / 跑1个例子 / 交1句复盘",
        updatedAt: new Date().toISOString()
      }]
    }
  }, studentA.token);
  assert.equal(rescueSnapshot.status, 200);

  const rescueOverview = await request("GET", "/api/teacher/overview", null, teacher.token);
  assert.equal(rescueOverview.status, 200);
  assert.ok(rescueOverview.body.overview.recentReviewNotes.some((note) => (
    note.source === "个人补救任务"
    && note.tag === "闭环证据"
    && String(note.evidence || "").includes("补救路径")
  )));
  const studentBrief = rescueOverview.body.overview.students.find((item) => item.userId === studentA.user.id)?.learningBrief;
  assert.ok(studentBrief);
  assert.ok(String(studentBrief.status || "").includes("闭环证据"));
  const rescueProgress = rescueOverview.body.overview.assignmentProgress.find((item) => item.id === rescue.body.assignment.id);
  assert.ok(rescueProgress);
  const rescueStudent = rescueProgress.studentStatus.find((item) => item.userId === studentA.user.id);
  assert.ok(rescueStudent);
  assert.ok(rescueStudent.rescueEvidence);
  assert.equal(rescueStudent.rescueEvidence.tag, "闭环证据");

  console.log("targeted-assignment-logic-ok");
  process.exit(0);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
`;

const result = spawnSync(process.execPath, ["-e", script], {
  cwd: root,
  env,
  encoding: "utf8",
  timeout: 20_000
});

if (result.stdout.trim()) console.log(result.stdout.trim());
if (result.stderr.trim()) console.error(result.stderr.trim());
fs.rmSync(tempDir, { recursive: true, force: true });

if (result.status !== 0) process.exit(result.status || 1);
