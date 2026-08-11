const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const root = path.join(__dirname, "..");
const backendRoot = path.join(root, "backend", "node");
const privateRoot = path.resolve(process.env.STRUCTIFY_PRIVATE_ROOT || path.join(root, "private"));
const presentationDir = path.join(privateRoot, "presentation-materials");
const requirePrivateResources = process.env.STRUCTIFY_REQUIRE_PRIVATE_RESOURCES === "true";
const dbPath = path.join(os.tmpdir(), `ds-presentation-live-${process.pid}-${Date.now()}.db`);
const secret = "presentation-live-assets-test-secret";

const lessonId = "01-01A";
const slideId = "ch01-deck01-df791c7-s003";
const imagePath = "rendered/ch01-deck01-df791c7/003.png";
const expectedImageSha256 = "66c6ff840a297dc1e2950ffd1c845d23074ae6d734bbfaf36afbd3813ddc0c6c";
const expectedIntroScene = {
  slides: ["ch01-deck01-df791c7-s003", "ch01-deck01-df791c7-s004"],
  primarySlideId: slideId,
  coverage: "direct",
  score: 0.828
};
const expectedSlide = {
  id: slideId,
  deckId: "ch01-deck01-df791c7",
  deckTitle: "数据结构-第一章-绪论01",
  slideNumber: 3,
  chapter: "01",
  title: "学校简介",
  rawText: "学校简介 1.1 数据结构的基础概念",
  speakerNotes: "数据结构的相关名词 数据（ Data ） 数据元素（ Data Element ） 数据对象（ Data Object ） 数据结构（ Data Structure ） 数据类型（ Data Type ） 数据抽象与抽象数据类型",
  semanticSummary: "1.1节的基础概念列表，引出后续定义。",
  teachingRole: "overview",
  teachingFocus: "预告本节将介绍的核心术语，为后续定义做铺垫。",
  concepts: ["数据", "数据元素", "数据对象", "数据结构", "数据类型"],
  visualAnchors: ["概念列表"],
  animationCapabilities: []
};

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

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

function hasMountedPrivateResources() {
  return ["presentation-materials", "knowledge", "pdfs", "source-ppt"]
    .some((directory) => fs.existsSync(path.join(privateRoot, directory)));
}

function verifyExternalResourceBoundary() {
  if (fs.existsSync(presentationDir)) return true;

  if (hasMountedPrivateResources()) {
    throw new Error("PRESENTATION_LIVE_ASSETS_BUNDLE_INCOMPLETE");
  }

  const message = "PRESENTATION_LIVE_ASSETS_EXTERNAL_RESOURCES_REQUIRED";
  if (requirePrivateResources) throw new Error(message);

  console.log(message);
  return false;
}

async function main() {
  if (!verifyExternalResourceBoundary()) return;

  const fixtureImagePath = path.join(presentationDir, ...imagePath.split("/"));
  const fixtureImage = fs.readFileSync(fixtureImagePath);
  assert.ok(fixtureImage.length > 0, "the live presentation fixture image must be non-empty");
  assert.equal(sha256(fixtureImage), expectedImageSha256, "the known live fixture must not drift silently");

  const port = await getFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const child = spawn(process.execPath, ["server.js"], {
    cwd: backendRoot,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      DB_PATH: dbPath,
      JWT_SECRET: secret,
      STRUCTIFY_PRIVATE_ROOT: privateRoot,
      PRESENTATION_DIR: "",
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

    const anonymousPlan = await fetch(`${baseUrl}/api/classroom/presentation-plan?lessonId=${lessonId}`);
    assert.equal(anonymousPlan.status, 401, "presentation plans must require a session");

    const headers = { authorization: `Bearer ${tokenFor(1, "presentation-live@example.test")}` };
    const planResponse = await fetch(`${baseUrl}/api/classroom/presentation-plan?lessonId=${lessonId}`, { headers });
    assert.equal(planResponse.status, 200);
    const payload = await planResponse.json();
    assert.equal(payload.ok, true);
    assert.equal(payload.ready, true);
    assert.equal(payload.lessonId, lessonId);
    assert.equal(payload.plan.lessonId, lessonId);
    assert.equal(payload.plan.title, "数据、数据元素与数据对象");
    assert.deepEqual(Object.keys(payload.plan.scenes).sort(), [
      "concept-one",
      "concept-two",
      "intro",
      "practice",
      "summary",
      "transfer"
    ]);
    assert.deepEqual(payload.plan.scenes.intro, expectedIntroScene);
    assert.deepEqual(payload.plan.slideOrder, [
      "ch01-deck01-df791c7-s003",
      "ch01-deck01-df791c7-s004",
      "ch01-deck01-df791c7-s007",
      "ch01-deck01-df791c7-s008",
      "ch01-deck01-df791c7-s017",
      "ch01-deck01-df791c7-s062"
    ]);

    const slide = payload.slides[slideId];
    assert.ok(slide, "the known lesson slide must be present in the public plan payload");
    assert.deepEqual(
      Object.fromEntries(Object.keys(expectedSlide).map((key) => [key, slide[key]])),
      expectedSlide,
      "the public page metadata must retain the known slide values"
    );
    assert.ok(slide.title.trim(), "the slide title must be non-empty");
    assert.ok(slide.rawText.trim(), "the slide text must be non-empty");

    const signedImageUrl = new URL(slide.imageUrl, baseUrl);
    assert.equal(signedImageUrl.pathname, `/presentation/${imagePath}`);
    assert.match(signedImageUrl.searchParams.get("expires") || "", /^\d+$/);
    assert.match(signedImageUrl.searchParams.get("token") || "", /^[a-f0-9]{64}$/);

    const anonymousImage = await fetch(`${baseUrl}/presentation/${imagePath}`);
    assert.equal(anonymousImage.status, 401, "canonical presentation assets must not be enumerable anonymously");

    const imageResponse = await fetch(signedImageUrl);
    assert.equal(imageResponse.status, 200, "a signed public image URL must serve the known asset");
    assert.equal(imageResponse.headers.get("content-type"), "image/png");
    assert.equal(imageResponse.headers.get("x-content-type-options"), "nosniff");
    const imageBody = Buffer.from(await imageResponse.arrayBuffer());
    assert.ok(imageBody.length > 0, "the HTTP image response must be non-empty");
    assert.deepEqual(imageBody, fixtureImage, "the signed image response must match the live fixture exactly");
    assert.equal(sha256(imageBody), expectedImageSha256);

    const missingImage = await fetch(`${baseUrl}/presentation/rendered/ch01-deck01-df791c7/not-found.png`, { headers });
    assert.equal(missingImage.status, 404, "a missing presentation asset must be rejected safely");

    const invalidImage = await fetch(`${baseUrl}/presentation/slides.json`, { headers });
    assert.equal(invalidImage.status, 404, "presentation metadata must not be served as an asset");

    console.log(`presentation-live-assets-ok lesson=${lessonId} slide=${slideId} signed-image=served invalid-assets=blocked`);
  } finally {
    await stopChild(child);
    fs.rmSync(dbPath, { force: true });
    fs.rmSync(`${dbPath}-shm`, { force: true });
    fs.rmSync(`${dbPath}-wal`, { force: true });
  }

  if (stderr.trim()) process.stderr.write(stderr);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
