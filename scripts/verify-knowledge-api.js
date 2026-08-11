const assert = require("assert");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const root = path.join(__dirname, "..");
const nodeRoot = path.join(root, "backend", "node");

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

function createFixture() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-agent-knowledge-api-"));
  const knowledgeDir = path.join(tempDir, "knowledge");
  const lessonDir = path.join(knowledgeDir, "lessons");
  fs.mkdirSync(lessonDir, { recursive: true });
  fs.writeFileSync(path.join(lessonDir, "03-01-栈与队列-栈的定义和表示实现.md"), `
# 课时编号：03-01
# 课时标题：栈与队列-栈的定义和表示实现

## 2. 本课时范围
- 栈的定义、顺序栈和链栈。

## 3. 教材原文整理
栈是只允许在一端进行插入和删除的线性表，具有后进先出的特点。

## 6. 本课时知识点清单
- LIFO
- push、pop 和 top
`, "utf8");
  return { tempDir, knowledgeDir, dbPath: path.join(tempDir, "test.db") };
}

async function waitForHealth(baseUrl, child) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`server exited early with code ${child.exitCode}`);
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return response.json();
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 120));
  }
  throw new Error("server did not become healthy in time");
}

async function main() {
  const fixture = createFixture();
  const port = await getFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const child = spawn(process.execPath, ["server.js"], {
    cwd: nodeRoot,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: fixture.dbPath,
      KNOWLEDGE_DIR: fixture.knowledgeDir,
      KNOWLEDGE_DEBUG_API: "true",
      MODEL_API_KEY: "test-key-not-used",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: ""
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let stderr = "";
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });

  try {
    const health = await waitForHealth(baseUrl, child);
    assert.strictEqual(health.knowledge?.ready, true, "health check should expose a ready knowledge index");
    assert.strictEqual(health.knowledge?.lessonCount, 1, "health check should expose indexed lesson count");

    const response = await fetch(`${baseUrl}/api/knowledge/search?q=${encodeURIComponent("栈为什么是后进先出")}`);
    assert.strictEqual(response.status, 200, "knowledge search endpoint should be available");
    const payload = await response.json();
    assert.strictEqual(payload.ok, true);
    assert.match(payload.results[0].title, /栈的定义/, "search endpoint should return the relevant lesson");
    assert.match(payload.results[0].source, /03-01-栈与队列/, "search endpoint should include a reviewable source path");

    const emptyResponse = await fetch(`${baseUrl}/api/knowledge/search?q=`);
    assert.strictEqual(emptyResponse.status, 400, "empty search queries should be rejected");
    console.log(`knowledge-api-ok lessons=${health.knowledge.lessonCount} results=${payload.results.length}`);
  } finally {
    child.kill();
    await new Promise((resolve) => {
      if (child.exitCode !== null) return resolve();
      child.once("exit", resolve);
      setTimeout(resolve, 1500);
    });
    fs.rmSync(fixture.tempDir, { recursive: true, force: true });
  }

  if (stderr.trim()) process.stderr.write(stderr);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
