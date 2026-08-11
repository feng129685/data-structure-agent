const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const dir = path.join(root, 'private', 'presentation-materials');
const slidesPath = path.join(dir, 'slides.json');
const plansPath = path.join(dir, 'lesson-presentation-plans.json');
function readJson(p){ try{return JSON.parse(fs.readFileSync(p,'utf8'));}catch{return null;} }
const slides = readJson(slidesPath);
const plans = readJson(plansPath);
if (!slides || !plans) {
  if (process.env.STRUCTIFY_REQUIRE_PRIVATE_RESOURCES !== 'true') {
    console.log('PPT_OFFLINE_EXTERNAL_RESOURCES_REQUIRED slides=0 lessons=0 missingImages=unknown');
    process.exit(0);
  }
  console.error('PPT_OFFLINE_NOT_READY');
  console.error('缺少 private/presentation-materials/slides.json 或 lesson-presentation-plans.json');
  console.error('请运行: scripts\\build-ppt-offline.cmd');
  process.exit(2);
}
const rows = Array.isArray(slides.slides) ? slides.slides : [];
const lessons = plans.lessons && typeof plans.lessons === 'object' ? plans.lessons : {};
let missingImages = 0;
for (const slide of rows) {
  const rel = String(slide.imagePath || '').replace(/[\\/]+/g, path.sep);
  if (!rel || !fs.existsSync(path.join(dir, rel))) missingImages++;
}
console.log(`PPT_OFFLINE_READY slides=${rows.length} lessons=${Object.keys(lessons).length} missingImages=${missingImages}`);
if (!rows.length || !Object.keys(lessons).length || missingImages) process.exit(3);
