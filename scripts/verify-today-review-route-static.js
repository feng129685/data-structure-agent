const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
const index = fs.readFileSync(path.join(root, "index.html"), "utf8");

const markers = [
  "function buildTodayReviewRoute",
  "function renderTodayReviewRoute",
  "function handleTodayRouteAction",
  "今日复习路线",
  "today-route-card",
  "data-today-route",
  "renderTodayReviewRoute(scenarioId)",
  "handleTodayRouteAction(button)",
  "今日先学",
  "推进老师任务"
];

const missingHtml = markers.filter((marker) => !html.includes(marker));
const missingIndex = markers.filter((marker) => !index.includes(marker));

if (missingHtml.length || missingIndex.length) {
  if (missingHtml.length) {
    console.error("Missing today review route prototype markers:");
    for (const marker of missingHtml) console.error(`- ${marker}`);
  }
  if (missingIndex.length) {
    console.error("Missing today review route index markers:");
    for (const marker of missingIndex) console.error(`- ${marker}`);
  }
  process.exit(1);
}

console.log(`today-review-route-static-ok markers=${markers.length}`);
