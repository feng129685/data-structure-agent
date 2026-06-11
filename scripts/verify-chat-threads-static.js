const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const server = fs.readFileSync(path.join(root, "server.js"), "utf8");

const serverMarkers = [
  "CREATE TABLE IF NOT EXISTS chat_threads",
  "function handleGetChatThreads",
  "async function handleUpsertChatThread",
  "function handleDeleteChatThread",
  "\"/api/chat-threads\"",
  "DELETE FROM chat_threads"
];

const frontendMarkers = [
  "chatThreads: []",
  "activeCoachThreadId",
  "activeClassroomThreadId",
  "function renderThreadDrawer",
  "async function saveActiveThread",
  "async function startNewThread",
  "function restoreThread",
  "async function deleteThread",
  "data-thread-new=\"coach\"",
  "data-thread-open=\"classroom\"",
  "startNewThread(button.dataset.threadNew)",
  "openThreadDrawer(button.dataset.threadOpen)",
  "els.threadDrawerClose.addEventListener(\"click\", closeThreadDrawer)",
  "event.target === els.threadDrawerOverlay",
  "event.key === \"Escape\" && els.threadDrawerOverlay",
  "lastFocusedBeforeThreadDrawer",
  "loadChatThreadsFromServer",
  "saveChatThreadToServer",
  "deleteChatThreadOnServer",
  "const activeId = activeThreadId(\"coach\")",
  "deleteChatThreadOnServer(activeId)"
];

const missingServer = serverMarkers.filter((marker) => !server.includes(marker));
const missingFrontend = frontendMarkers.filter((marker) => !html.includes(marker));

if (missingServer.length || missingFrontend.length) {
  if (missingServer.length) {
    console.error("Missing server chat-thread markers:");
    for (const marker of missingServer) console.error(`- ${marker}`);
  }
  if (missingFrontend.length) {
    console.error("Missing frontend chat-thread markers:");
    for (const marker of missingFrontend) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`chat-threads-static-ok server=${serverMarkers.length} frontend=${frontendMarkers.length}`);
