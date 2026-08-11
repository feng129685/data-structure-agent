const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-presentation-'));
fs.mkdirSync(path.join(tmp, 'rendered', 'deck-a'), { recursive: true });
fs.writeFileSync(path.join(tmp, 'rendered', 'deck-a', '001.png'), Buffer.from([137,80,78,71]));
fs.writeFileSync(path.join(tmp, 'slides.json'), JSON.stringify({ slides: [{
  id:'deck-a-s001', deckId:'deck-a', deckTitle:'树', slideNumber:1, title:'中序遍历', rawText:'左根右',
  semanticSummary:'中序遍历顺序', teachingFocus:'结合树图说明', imagePath:'rendered/deck-a/001.png'
}] }));
fs.writeFileSync(path.join(tmp, 'lesson-presentation-plans.json'), JSON.stringify({ lessons: {
  '06-03A': { title:'二叉树遍历', scenes:{ 'concept-one':{ slides:['deck-a-s001'], primarySlideId:'deck-a-s001' } }, slideOrder:['deck-a-s001'] }
} }));
process.env.PRESENTATION_DIR = tmp;
const runtime = require('../backend/node/presentation-runtime');
const bundle = runtime.getLessonPresentationPlan('06-03A');
assert.equal(bundle.ready, true);
assert.equal(bundle.plan.scenes['concept-one'].slides[0], 'deck-a-s001');
assert.equal(bundle.slides['deck-a-s001'].title, '中序遍历');
assert.ok(bundle.slides['deck-a-s001'].imageUrl.includes('/presentation/'));
const asset = runtime.resolvePresentationAsset('/presentation/rendered/deck-a/001.png');
assert.equal(asset, path.join(tmp,'rendered','deck-a','001.png'));
assert.equal(runtime.resolvePresentationAsset('/presentation/../../etc/passwd'), null);
assert.equal(runtime.resolvePresentationAsset('/presentation/slides.json'), null);
console.log('presentation-runtime-ok lesson=06-03A slide=deck-a-s001');
