const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "frontend", "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "backend", "node", "server.js"), "utf8");

const serverMarkers = [
  "CREATE TABLE IF NOT EXISTS learning_snapshots",
  "function normalizeLearningSnapshot",
  "function rowToLearningSnapshot",
  "function handleGetLearningSnapshot",
  "async function handleSaveLearningSnapshot",
  "function handleDeleteLearningSnapshot",
  "\"/api/learning-snapshot\"",
  "INSERT INTO learning_snapshots",
  "DELETE FROM learning_snapshots"
];

const frontendMarkers = [
  "let learningSnapshotSaveTimer = null",
  "let learningSnapshotSyncing = false",
  "let learningSnapshotLoaded = false",
  "function buildLearningSnapshotPayload",
  "function mergeLearningSnapshot",
  "async function loadLearningSnapshotFromServer",
  "async function saveLearningSnapshotToServer",
  "function scheduleLearningSnapshotSync",
  "async function deleteLearningSnapshotOnServer",
  "apiFetch(\"/api/learning-snapshot\"",
  "scheduleLearningSnapshotSync();",
  "await loadLearningSnapshotFromServer();",
  "await deleteLearningSnapshotOnServer();",
  "学习报告已云端同步",
  "study-report-sync"
];

const missingServer = serverMarkers.filter((marker) => !server.includes(marker));
const missingFrontend = frontendMarkers.filter((marker) => !html.includes(marker));

if (missingServer.length || missingFrontend.length) {
  if (missingServer.length) {
    console.error("Missing server learning-snapshot markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  if (missingFrontend.length) {
    console.error("Missing frontend learning-snapshot markers:");
    for (const marker of missingFrontend) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`learning-snapshot-static-ok server=${serverMarkers.length} frontend=${frontendMarkers.length}`);
