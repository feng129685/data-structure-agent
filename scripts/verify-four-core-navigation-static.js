const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "..", "frontend");
const entries = ["index.html", "prototype.html"];
const expectedViews = ["home", "coach", "mainline", "presentation", "knowledge"];

for (const entry of entries) {
  const html = fs.readFileSync(path.join(root, entry), "utf8");
  const nav = html.match(/<nav class="rail-nav" id="viewNav"[\s\S]*?<\/nav>/)?.[0] || "";
  assert.ok(nav, `${entry} must expose the primary navigation`);

  const views = Array.from(nav.matchAll(/<button\b[^>]*\bdata-view="([^"]+)"/g), (match) => match[1]);
  assert.deepEqual(views, expectedViews, `${entry} primary navigation must contain the overview and four meeting modules in order`);
  assert.match(nav, />问答<\/button>/, `${entry} must label the coach entry as 问答`);
  assert.match(nav, />主线学习<\/button>/, `${entry} must label the textbook path explicitly`);
  assert.match(nav, />PPT 学习<\/button>/, `${entry} must expose a dedicated PPT entry`);
  assert.match(nav, />知识库<\/button>/, `${entry} must expose a dedicated knowledge entry`);

  for (const toolView of ["classroom", "animation", "compiler", "materials"]) {
    assert.ok(!views.includes(toolView), `${entry} must keep ${toolView} out of primary navigation`);
  }

  assert.match(html, /const appViews = \[[^\]]*"mainline"[^\]]*"presentation"[^\]]*"knowledge"[^\]]*\]/, `${entry} must route all four modules`);
  assert.match(html, /if \(view === "materials"[^\n]*return "mainline";/, `${entry} must retain the legacy materials route as a mainline alias`);
}

const index = fs.readFileSync(path.join(root, "index.html"), "utf8");
const prototype = fs.readFileSync(path.join(root, "prototype.html"), "utf8");
assert.equal(prototype, index, "prototype.html must share the canonical entry instead of keeping an independent old flow");

console.log("four-core-navigation-static-ok entries=2 modules=4");
