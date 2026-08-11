const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "..", "frontend");
const files = ["index.html", "prototype.html"];

const required = [
  ["<main class=\"mainline-view\" id=\"mainlineView\"", "mainline view container"],
  ["id=\"mainlineChapterList\"", "mainline chapter directory"],
  ["id=\"mainlineLessonList\"", "mainline lesson directory"],
  ["id=\"mainlineDetail\"", "mainline lesson detail"],
  ["data-mainline-action=\"coach\"", "mainline question handoff"],
  ["data-mainline-action=\"presentation\"", "mainline PPT handoff"],
  ["data-mainline-action=\"animation\"", "mainline animation handoff"],
  ["data-mainline-action=\"compiler\"", "mainline compiler handoff"],
  ["<main class=\"presentation-view\" id=\"presentationView\"", "presentation view container"],
  ["data-presentation-page", "presentation current page indicator"],
  ["data-presentation-prev", "presentation previous control"],
  ["data-presentation-next", "presentation next control"],
  ["data-presentation-retry", "presentation retry control"],
  ["data-presentation-reselect", "presentation reselect control"],
  ["data-presentation-ask", "presentation question handoff"],
  ["loading=\"lazy\"", "presentation images must be lazy loaded"],
  ["currentClassroomPresentationContext()", "presentation context bridge"],
  ["<main class=\"knowledge-view\" id=\"knowledgeView\"", "knowledge view container"],
  ["id=\"knowledgeQuery\"", "knowledge query input"],
  ["id=\"knowledgeChapterFilter\"", "knowledge chapter filter"],
  ["id=\"knowledgeSourceFilter\"", "knowledge source filter"],
  ["id=\"knowledgeRelevanceFilter\"", "knowledge relevance filter"],
  ["id=\"knowledgeReviewFilter\"", "knowledge review filter"],
  ["id=\"knowledgeResults\"", "knowledge results state"],
  ["filterKnowledgeResults", "knowledge result filtering"],
  ["knowledgeReviewStatus", "knowledge review policy"],
  ["/api/v1/knowledge/search", "knowledge Spring backend boundary"],
  ["/api/knowledge/search", "knowledge legacy backend boundary"],
  ["待人工复核", "knowledge must expose unreviewed status"],
  ["mainlineProgress", "mainline progress persistence"],
  ["renderMainlineView", "mainline renderer"],
  ["renderPresentationView", "presentation renderer"],
  ["renderKnowledgeView", "knowledge renderer"],
  ["COURSE_CATALOG", "real curriculum catalog"],
  ["body[data-view=\"mainline\"]", "mainline responsive route styling"],
  ["body[data-view=\"presentation\"]", "presentation responsive route styling"],
  ["body[data-view=\"knowledge\"]", "knowledge responsive route styling"]
];

for (const file of files) {
  const html = fs.readFileSync(path.join(root, file), "utf8");
  for (const [needle, label] of required) {
    assert.ok(html.includes(needle), `${file} missing ${label}: ${needle}`);
  }
  assert.match(html, /class="[^"]*presentation[^"]*"[^>]*>[\s\S]*?上一页/, `${file} must provide readable presentation navigation copy`);
  assert.match(html, /class="[^"]*knowledge[^"]*"[^>]*>[\s\S]*?审核/, `${file} must provide readable knowledge review copy`);
}

const index = fs.readFileSync(path.join(root, "index.html"), "utf8");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
assert.equal(prototype, index, "prototype.html must remain the canonical entry mirror");

console.log(`four-core-ui-static-ok files=${files.length} markers=${required.length}`);
