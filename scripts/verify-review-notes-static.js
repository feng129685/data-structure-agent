const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "frontend", "index.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const htmlMarkers = [
  "reviewNotes: []",
  "function sanitizeReviewNotes",
  "function mergeReviewNotes",
  "function recordReviewNote",
  "reviewNotes: sanitizeReviewNotes(state.reviewNotes || [])",
  "state.reviewNotes = mergeReviewNotes",
  "tag: String(item.tag || \"\").slice(0, 20)",
  "evidence: String(item.evidence || \"\").slice(0, 80)",
  "profile-review-evidence",
  "闭环证据",
  "recordReviewNote({",
  "source: \"动画复盘\"",
  "source: \"C 实验复盘\"",
  "source: \"老师任务\"",
  "profile-review-list",
  "复盘足迹",
  "reviewNotes = sanitizeReviewNotes"
];

const serverMarkers = [
  "function normalizeReviewNotes",
  "reviewNotes: normalizeReviewNotes",
  "const reviewNotes = normalizeReviewNotes(value.reviewNotes)",
  "tag: normalizeText(item.tag || \"\", 20)",
  "evidence: normalizeText(item.evidence || \"\", 80)",
  "证据",
  "最近复盘足迹：",
  "context.reviewNotes.length"
];

const missingHtml = htmlMarkers.filter((marker) => !html.includes(marker));
const missingIndex = htmlMarkers.filter((marker) => !index.includes(marker));
const missingServer = serverMarkers.filter((marker) => !server.includes(marker));

if (missingHtml.length || missingIndex.length || missingServer.length) {
  if (missingHtml.length) {
    console.error("Missing review notes prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing review notes index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  if (missingServer.length) {
    console.error("Missing review notes server markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`review-notes-static-ok html=${htmlMarkers.length} server=${serverMarkers.length}`);
