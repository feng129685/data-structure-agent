const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert');
const vm = require('node:vm');
const root = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(root,'frontend','index.html'),'utf8');
const server = fs.readFileSync(path.join(root,'backend','node','server.js'),'utf8');
const plansPath = path.join(root, 'private', 'presentation-materials', 'lesson-presentation-plans.json');
const plansDocument = fs.existsSync(plansPath)
  ? JSON.parse(fs.readFileSync(plansPath, 'utf8'))
  : null;
for (const needle of [
  'ensureClassroomPresentationPlan',
  'renderClassroomPresentationStatus',
  'currentClassroomPresentationContext',
  'renderClassroomPresentationSlide',
  'data-classroom-ppt-image',
  'presentation: currentClassroomPresentationContext()'
]) assert.ok(html.includes(needle), `missing frontend marker ${needle}`);
for (const needle of [
  '/api/classroom/presentation-plan',
  'getLessonPresentationPlan',
  'normalizeClassroomPresentation',
  '当前右侧 PPT：',
  'servePresentationAsset'
]) assert.ok(server.includes(needle), `missing server marker ${needle}`);

const mapSource = html.match(/const classroomPresentationLessonMap = \{([\s\S]*?)\n\s*\};/);
assert.ok(mapSource, 'missing classroom presentation lesson map');
const mappings = Object.fromEntries(
  Array.from(mapSource[1].matchAll(/([a-z][a-z0-9_-]*):\s*"([^"]+)"/g), (match) => [match[1], match[2]])
);
const expectedTitlePatterns = {
  choose: /选择/,
  stack: /栈的定义/,
  list: /查找、插入与删除/,
  tree: /遍历/,
  queue: /循环队列/,
  heap: /堆排序/,
  hash: /冲突/,
  quiz: /综合练习/
};
if (!plansDocument && process.env.STRUCTIFY_REQUIRE_PRIVATE_RESOURCES === 'true') {
  throw new Error('presentation lesson plans are required for this resource-enforced run');
}
const plans = plansDocument?.lessons || Object.fromEntries(
  Object.entries(mappings).map(([scenario, lessonId]) => [
    lessonId,
    // Release tests intentionally contain no private courseware. The regex
    // source is a structural sentinel, never learner-facing lesson content.
    { title: expectedTitlePatterns[scenario].source }
  ])
);
const planSource = plansDocument ? 'private-manifest' : 'contract-fixture';
assert.deepEqual(Object.keys(mappings).sort(), Object.keys(expectedTitlePatterns).sort(), 'scenario mappings should stay complete');
for (const [scenario, titlePattern] of Object.entries(expectedTitlePatterns)) {
  const lessonId = mappings[scenario];
  assert.ok(plans[lessonId], `scenario ${scenario} references missing lesson ${lessonId}`);
  assert.match(plans[lessonId].title, titlePattern, `scenario ${scenario} references unrelated lesson ${lessonId}`);
}

const storageHelperSource = html.match(/    function classroomStateForStorage\(classroom\) \{[\s\S]*?\n    \}/)?.[0];
assert.ok(storageHelperSource, 'missing classroom presentation storage boundary');
const storageSandbox = {};
vm.runInNewContext(`${storageHelperSource}\nstored = classroomStateForStorage({
  lessonId: "03-01A",
  presentationSlideId: "deck-a-s001",
  presentationPlan: { slideOrder: ["deck-a-s001"] },
  presentationSlides: { "deck-a-s001": { rawText: "large slide payload" } },
  presentationStatus: "ready",
  presentationError: "stale error",
  presentationImageFailed: true
});`, storageSandbox);
assert.deepEqual(JSON.parse(JSON.stringify(storageSandbox.stored)), {
  lessonId: '03-01A',
  presentationSlideId: 'deck-a-s001'
});
assert.ok(html.includes('classroom: classroomStateForStorage(state.classroom)'), 'saveState should use the bounded classroom payload');
for (const needle of [
  'presentationPlan: base.presentationPlan',
  'presentationSlides: base.presentationSlides',
  'presentationStatus: base.presentationStatus',
  'presentationError: base.presentationError',
  'presentationImageFailed: base.presentationImageFailed'
]) assert.ok(html.includes(needle), `stored presentation runtime state should be ignored: ${needle}`);

const loadFunctionSource = html.match(/    async function ensureClassroomPresentationPlan\([\s\S]*?\n    \}\n\n    function currentClassroomPresentationSlide/)?.[0] || '';
assert.ok(loadFunctionSource.includes('const isCurrentRequest = () => state.classroom.lessonId === id;'), 'presentation loads should identify stale responses');
assert.ok((loadFunctionSource.match(/if \(!isCurrentRequest\(\)\) return bundle;/g) || []).length >= 2, 'successful stale presentation loads should not mutate current state');
assert.ok(loadFunctionSource.includes('if (!isCurrentRequest()) return null;'), 'failed stale presentation loads should not mutate current state');
assert.ok(loadFunctionSource.includes('if (isCurrentRequest()) renderClassroomPresentation();'), 'stale presentation loads should not rerender the current lesson');

console.log(`presentation-ui-static-ok mappings=${Object.keys(mappings).length} source=${planSource}`);
