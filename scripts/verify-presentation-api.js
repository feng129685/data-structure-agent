const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const root = path.join(__dirname, "..");
const backendRoot = path.join(root, "backend", "node");
const secret = "presentation-api-test-secret-32-characters";

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

function tokenFor(userId, email) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const header = encode({ alg: "HS256", typ: "JWT" });
  const now = Math.floor(Date.now() / 1000);
  const payload = encode({ userId, email, iat: now, exp: now + 3600 });
  const signature = crypto.createHmac("sha256", secret).update(`${header}.${payload}`).digest("base64url");
  return `${header}.${payload}.${signature}`;
}

async function waitForHealth(baseUrl, child) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`server exited early with code ${child.exitCode}`);
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("server did not become healthy in time");
}

async function stopChild(child) {
  if (child.exitCode !== null) return;
  child.kill();
  await Promise.race([
    new Promise((resolve) => child.once("exit", resolve)),
    new Promise((resolve) => setTimeout(resolve, 1500))
  ]);
}

function createFixture() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "ds-presentation-api-"));
  const presentationDir = path.join(tempDir, "presentation-materials");
  const imageDir = path.join(presentationDir, "rendered", "deck-a");
  const image = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  fs.mkdirSync(imageDir, { recursive: true });
  fs.writeFileSync(path.join(imageDir, "001.png"), image);
  fs.writeFileSync(path.join(tempDir, "secret.png"), Buffer.from("not public"));
  fs.writeFileSync(path.join(presentationDir, "slides.json"), JSON.stringify({
    builtAt: "2026-08-08T00:00:00Z",
    slides: [{
      id: "deck-a-s001",
      deckId: "deck-a",
      deckTitle: "栈",
      slideNumber: 1,
      title: "栈的定义",
      rawText: "后进先出",
      imagePath: "rendered/deck-a/001.png"
    }]
  }));
  fs.writeFileSync(path.join(presentationDir, "lesson-presentation-plans.json"), JSON.stringify({
    builtAt: "2026-08-08T00:00:00Z",
    lessons: {
      "03-01A": {
        title: "栈的定义与顺序栈",
        scenes: {
          intro: { slides: ["deck-a-s001"], primarySlideId: "deck-a-s001" }
        },
        slideOrder: ["deck-a-s001"]
      }
    }
  }));
  return { tempDir, presentationDir, image, dbPath: path.join(tempDir, "test.db") };
}

async function main() {
  const fixture = createFixture();
  const port = await getFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const child = spawn(process.execPath, ["server.js"], {
    cwd: backendRoot,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: fixture.dbPath,
      JWT_SECRET: secret,
      PRESENTATION_DIR: fixture.presentationDir,
      FRONTEND_DIR: path.join(root, "frontend"),
      MODEL_API_KEY: "",
      MIMO_API_KEY: "",
      SMTP_HOST: "",
      SMTP_USER: "",
      SMTP_PASS: "",
      SMTP_FROM: ""
    },
    stdio: ["ignore", "ignore", "pipe"]
  });
  let stderr = "";
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });

  try {
    await waitForHealth(baseUrl, child);

    const guest = await fetch(`${baseUrl}/api/classroom/presentation-plan?lessonId=03-01A`);
    assert.equal(guest.status, 401, "presentation plans should require authentication");

    const headers = { authorization: `Bearer ${tokenFor(1, "presentation@example.test")}` };
    const planResponse = await fetch(`${baseUrl}/api/classroom/presentation-plan?lessonId=03-01A`, { headers });
    assert.equal(planResponse.status, 200);
    const payload = await planResponse.json();
    assert.equal(payload.ok, true);
    assert.equal(payload.ready, true);
    assert.equal(payload.plan.lessonId, "03-01A");
    assert.match(
      payload.slides["deck-a-s001"].imageUrl,
      /^\/presentation\/rendered\/deck-a\/001\.png\?expires=\d+&token=[a-f0-9]{64}$/
    );

    const missingPlan = await fetch(`${baseUrl}/api/classroom/presentation-plan?lessonId=missing`, { headers });
    assert.equal(missingPlan.status, 200);
    assert.equal((await missingPlan.json()).plan, null);

    const guestImage = await fetch(`${baseUrl}/presentation/rendered/deck-a/001.png`);
    assert.equal(guestImage.status, 401, "presentation images should not be enumerable by guests");

    const imageResponse = await fetch(`${baseUrl}${payload.slides["deck-a-s001"].imageUrl}`);
    assert.equal(imageResponse.status, 200);
    assert.equal(imageResponse.headers.get("content-type"), "image/png");
    assert.equal(imageResponse.headers.get("x-content-type-options"), "nosniff");
    assert.deepEqual(Buffer.from(await imageResponse.arrayBuffer()), fixture.image);

    const authenticatedImage = await fetch(`${baseUrl}/presentation/rendered/deck-a/001.png`, { headers });
    assert.equal(authenticatedImage.status, 200, "authenticated clients may request the canonical asset path");

    const signedUrl = new URL(`${baseUrl}${payload.slides["deck-a-s001"].imageUrl}`);
    signedUrl.searchParams.set("token", "0".repeat(64));
    assert.equal((await fetch(signedUrl)).status, 401, "tampered presentation signatures must fail");

    const traversal = await fetch(`${baseUrl}/presentation/..%2Fsecret.png`, { headers });
    assert.equal(traversal.status, 404, "presentation assets must stay inside their public directory");

    const metadata = await fetch(`${baseUrl}/presentation/slides.json`, { headers });
    assert.equal(metadata.status, 404, "presentation metadata must not be exposed as a public asset");

    for (const pathname of ["/", "/index.html", "/prototype.html"]) {
      const page = await fetch(`${baseUrl}${pathname}`);
      assert.equal(page.status, 200, `${pathname} should resolve to a frontend entry`);
      assert.match(page.headers.get("content-type") || "", /^text\/html/);
      await page.arrayBuffer();
    }

    console.log("presentation-api-ok auth=required signed-image=served metadata=private traversal=blocked entries=3");
  } finally {
    await stopChild(child);
    fs.rmSync(fixture.tempDir, { recursive: true, force: true });
  }

  if (stderr.trim()) process.stderr.write(stderr);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
