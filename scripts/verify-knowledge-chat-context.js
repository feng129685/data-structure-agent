const assert = require("assert");
const fs = require("fs");
const http = require("http");
const net = require("net");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const root = path.join(__dirname, "..");

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
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-agent-knowledge-chat-"));
  const knowledgeDir = path.join(tempDir, "knowledge");
  const lessonDir = path.join(knowledgeDir, "lessons");
  fs.mkdirSync(lessonDir, { recursive: true });
  fs.writeFileSync(path.join(lessonDir, "02-03-线性表-单链表及基本运算.md"), `
# 课时编号：02-03
# 课时标题：线性表-单链表及基本运算

## 2. 本课时范围
- 单链表插入和删除。

## 3. 教材原文整理
单链表插入新结点时，应先让新结点指向原后继结点，再修改前驱结点的 next 指针。

## 6. 本课时知识点清单
- 单链表插入的指针顺序
`, "utf8");
  return { tempDir, knowledgeDir, dbPath: path.join(tempDir, "test.db") };
}

async function waitForHealth(baseUrl, child) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`app server exited early with code ${child.exitCode}`);
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 120));
  }
  throw new Error("app server did not become healthy in time");
}

async function main() {
  const fixture = createFixture();
  const modelPort = await getFreePort();
  const appPort = await getFreePort();
  const capturedRequests = [];

  const modelServer = http.createServer((req, res) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", () => {
      const capturedRequest = JSON.parse(body);
      capturedRequests.push(capturedRequest);
      if (capturedRequest.stream) {
        res.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
        const chunks = [
          "请先保存后继指针。\n\n",
          "再修改前驱结点的 next。课本第 999 页也这样说。需要我再生成一个链表交互动画吗？"
        ];
        res.end(chunks.map((content) => `data: ${JSON.stringify({ choices: [{ delta: { content } }] })}\n\n`).join("") + "data: [DONE]\n\n");
        return;
      }
      res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({
        model: "fixture-model",
        choices: [{ message: { role: "assistant", content: "请先保存后继指针，再修改前驱结点的 next。教材页 999 附近也这样说。需要我再生成一个链表交互动画吗？" } }]
      }));
    });
  });
  await new Promise((resolve, reject) => {
    modelServer.once("error", reject);
    modelServer.listen(modelPort, "127.0.0.1", resolve);
  });

  const child = spawn(process.execPath, ["server.js"], {
    cwd: root,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(appPort),
      DB_PATH: fixture.dbPath,
      KNOWLEDGE_DIR: fixture.knowledgeDir,
      MODEL_API_KEY: "fixture-key",
      MODEL_BASE_URL: `http://127.0.0.1:${modelPort}/v1`,
      MODEL_NAME: "fixture-model",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: ""
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  try {
    const appBaseUrl = `http://127.0.0.1:${appPort}`;
    await waitForHealth(appBaseUrl, child);
    const response = await fetch(`${appBaseUrl}/api/chat`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        prompt: "单链表插入为什么要先保存后继指针？",
        scenario: { chapter: "线性表", title: "单链表", lead: "观察指针变化", summary: [], references: [] },
        mode: { label: "讲概念" },
        history: [],
        stream: false
      })
    });
    assert.strictEqual(response.status, 200);
    const payload = await response.json();
    assert.ok(payload.knowledgeSources?.length > 0, "chat response should report used knowledge sources");
    assert.match(payload.knowledgeSources[0].source, /02-03-线性表-单链表/, "chat should report the retrieved lesson source");
    assert.match(payload.knowledgeSources[0].sourceLabel, /课时 02-03.*单链表/, "chat should expose a display-safe source label");
    assert.match(payload.knowledgeSources[0].reviewStatus, /课程整理稿|待人工复核/, "chat should expose review status");
    assert.doesNotMatch(payload.answer, /教材页\s*999|课本第\s*999\s*页/, "non-streaming answers must not expose model-invented page citations");
    assert.match(payload.answer, /回答下方的课程资料依据/, "non-streaming answers should redirect citations to verified source cards");
    assert.doesNotMatch(payload.answer, /需要我.*生成.*动画/, "non-streaming answers should leave animation invitations to the interactive UI");

    const streamResponse = await fetch(`${appBaseUrl}/api/chat`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        prompt: "单链表插入为什么要先保存后继指针？",
        scenario: { chapter: "线性表", title: "单链表", lead: "观察指针变化", summary: [], references: [] },
        mode: { label: "讲概念" },
        history: [],
        stream: true
      })
    });
    assert.strictEqual(streamResponse.status, 200);
    const streamText = await streamResponse.text();
    const streamAnswer = streamText.split("\n")
      .filter((line) => line.startsWith("data: ") && line.slice(6).trim() !== "[DONE]")
      .map((line) => {
        try { return JSON.parse(line.slice(6)).content || ""; } catch { return ""; }
      })
      .join("");
    assert.match(streamText, /"knowledgeSources"/, "stream response should include source metadata");
    assert.match(streamText, /课时 02-03/, "stream source metadata should use the display-safe label");
    assert.doesNotMatch(streamText, /教材页\s*999|课本第\s*999\s*页/, "streaming answers must not expose model-invented page citations");
    assert.match(streamText, /回答下方的课程资料依据/, "streaming answers should redirect citations to verified source cards");
    assert.doesNotMatch(streamText, /需要我.*生成.*动画/, "streaming answers should leave animation invitations to the interactive UI");
    assert.match(streamAnswer, /请先保存后继指针。\n\n再修改前驱结点/, "streaming answer cleanup must preserve paragraph boundaries");

    const serializedMessages = JSON.stringify(capturedRequests[0]?.messages || []);
    assert.match(serializedMessages, /课程教材检索结果/, "retrieved textbook context should be sent to the model");
    assert.match(
      serializedMessages,
      /先让新结点指向原后继结点/,
      `the relevant lesson excerpt should be sent to the model: ${serializedMessages}`
    );
    assert.match(serializedMessages, /不要编造教材原文、页码或源码对应关系/, "the model should receive OCR safety rules");
    assert.match(serializedMessages, /不要在回答正文中自行生成.*教材页/, "the model should leave citation rendering to the system");
    assert.match(serializedMessages, /不要在回答正文中重复询问是否生成动画/, "the model should leave animation invitations to the interactive UI");
    console.log(`knowledge-chat-context-ok sources=${payload.knowledgeSources.length}`);
  } finally {
    child.kill();
    modelServer.close();
    await new Promise((resolve) => {
      if (child.exitCode !== null) return resolve();
      child.once("exit", resolve);
      setTimeout(resolve, 1500);
    });
    fs.rmSync(fixture.tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
