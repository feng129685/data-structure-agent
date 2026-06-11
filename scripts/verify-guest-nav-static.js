const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const files = ["prototype.html", "index.html"];

const requiredMarkers = [
  "body.is-guest .home-entry-card[data-auth-required]::after",
  "content: \"登录\"",
  "showToast(`${feature}需要登录后使用`, \"info\")"
];

const forbiddenMarkers = [
  "body.is-guest [data-auth-required]::after"
];

const failures = [];

for (const file of files) {
  const text = fs.readFileSync(path.join(root, file), "utf8");
  const missing = requiredMarkers.filter((marker) => !text.includes(marker));
  const forbidden = forbiddenMarkers.filter((marker) => text.includes(marker));
  if (missing.length || forbidden.length) failures.push({ file, missing, forbidden });
}

if (failures.length) {
  for (const failure of failures) {
    if (failure.missing.length) {
      console.error(`Missing guest nav markers in ${failure.file}:`);
      for (const marker of failure.missing) console.error(`- ${marker}`);
    }
    if (failure.forbidden.length) {
      console.error(`Forbidden broad guest nav markers in ${failure.file}:`);
      for (const marker of failure.forbidden) console.error(`- ${marker}`);
    }
  }
  process.exit(1);
}

console.log(`guest-nav-static-ok files=${files.length} markers=${requiredMarkers.length}`);
