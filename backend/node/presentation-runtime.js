const fs = require('node:fs');
const path = require('node:path');

const WORKSPACE_ROOT = path.resolve(__dirname, '..', '..');
const PRIVATE_ROOT = path.resolve(process.env.STRUCTIFY_PRIVATE_ROOT || path.join(WORKSPACE_ROOT, 'private'));
const PRESENTATION_DIR = process.env.PRESENTATION_DIR || path.join(PRIVATE_ROOT, 'presentation-materials');
const PRESENTATION_RENDERED_DIR = path.join(PRESENTATION_DIR, 'rendered');
const SLIDES_PATH = path.join(PRESENTATION_DIR, 'slides.json');
const PLANS_PATH = path.join(PRESENTATION_DIR, 'lesson-presentation-plans.json');
const PUBLIC_IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.webp']);

let cache = { stamp: '', slidesById: new Map(), plans: {}, meta: null };

function safeReadJson(filePath, fallback) {
  try { return JSON.parse(fs.readFileSync(filePath, 'utf8')); } catch { return fallback; }
}

function currentStamp() {
  try {
    const a = fs.statSync(SLIDES_PATH).mtimeMs;
    const b = fs.statSync(PLANS_PATH).mtimeMs;
    return `${a}:${b}`;
  } catch { return 'missing'; }
}

function loadPresentationIndex() {
  const stamp = currentStamp();
  if (cache.stamp === stamp) return cache;
  const slidesDoc = safeReadJson(SLIDES_PATH, { version: 1, slides: [] });
  const plansDoc = safeReadJson(PLANS_PATH, { version: 1, lessons: {} });
  const slides = Array.isArray(slidesDoc.slides) ? slidesDoc.slides : [];
  cache = {
    stamp,
    slidesById: new Map(slides.filter(Boolean).map((slide) => [String(slide.id || ''), slide])),
    plans: plansDoc && plansDoc.lessons && typeof plansDoc.lessons === 'object' ? plansDoc.lessons : {},
    meta: {
      ready: stamp !== 'missing' && slides.length > 0,
      builtAt: slidesDoc.builtAt || plansDoc.builtAt || '',
      slideCount: slides.length,
      lessonCount: Object.keys(plansDoc?.lessons || {}).length
    }
  };
  return cache;
}

function publicSlideCard(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const imagePath = String(raw.imagePath || '').replace(/\\/g, '/').replace(/^\/+/, '');
  return {
    id: String(raw.id || '').slice(0, 120),
    deckId: String(raw.deckId || '').slice(0, 100),
    deckTitle: String(raw.deckTitle || '').slice(0, 160),
    slideNumber: Math.max(1, Number(raw.slideNumber) || 1),
    chapter: String(raw.chapter || '').slice(0, 12),
    title: String(raw.title || '').slice(0, 220),
    rawText: String(raw.rawText || '').slice(0, 5000),
    speakerNotes: String(raw.speakerNotes || '').slice(0, 2400),
    semanticSummary: String(raw.semanticSummary || '').slice(0, 700),
    teachingRole: String(raw.teachingRole || '').slice(0, 40),
    teachingFocus: String(raw.teachingFocus || '').slice(0, 700),
    concepts: Array.isArray(raw.concepts) ? raw.concepts.map(String).slice(0, 10) : [],
    visualAnchors: Array.isArray(raw.visualAnchors) ? raw.visualAnchors.map(String).slice(0, 8) : [],
    animationCapabilities: Array.isArray(raw.animationCapabilities) ? raw.animationCapabilities.map(String).slice(0, 12) : [],
    imageUrl: imagePath ? `/presentation/${imagePath.split('/').map(encodeURIComponent).join('/')}` : ''
  };
}

function getPresentationSlide(slideId) {
  const index = loadPresentationIndex();
  return publicSlideCard(index.slidesById.get(String(slideId || '').trim()));
}

function getLessonPresentationPlan(lessonId) {
  const index = loadPresentationIndex();
  const key = String(lessonId || '').trim();
  const rawPlan = index.plans[key];
  if (!rawPlan) return { ready: index.meta.ready, lessonId: key, plan: null, slides: {}, meta: index.meta };
  const slideIds = new Set();
  for (const scene of Object.values(rawPlan.scenes || {})) {
    for (const id of (scene?.slides || [])) slideIds.add(String(id));
    if (scene?.primarySlideId) slideIds.add(String(scene.primarySlideId));
  }
  const slides = {};
  for (const id of slideIds) {
    const card = publicSlideCard(index.slidesById.get(id));
    if (card) slides[id] = card;
  }
  return {
    ready: index.meta.ready,
    lessonId: key,
    plan: {
      lessonId: key,
      title: String(rawPlan.title || '').slice(0, 160),
      scenes: rawPlan.scenes && typeof rawPlan.scenes === 'object' ? rawPlan.scenes : {},
      slideOrder: Array.isArray(rawPlan.slideOrder) ? rawPlan.slideOrder.map(String) : []
    },
    slides,
    meta: index.meta
  };
}

function resolvePresentationAsset(pathname) {
  const prefix = '/presentation/';
  if (!String(pathname || '').startsWith(prefix)) return null;
  const encoded = String(pathname).slice(prefix.length);
  let decoded;
  try { decoded = encoded.split('/').map(decodeURIComponent).join('/'); } catch { return null; }
  if (!decoded || decoded.includes('\0')) return null;
  const normalized = path.normalize(decoded);
  if (path.isAbsolute(normalized) || normalized === '..' || normalized.startsWith(`..${path.sep}`)) return null;
  const filePath = path.resolve(PRESENTATION_DIR, normalized);
  const renderedRoot = path.resolve(PRESENTATION_RENDERED_DIR);
  const relative = path.relative(renderedRoot, filePath);
  if (!relative || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) return null;
  if (!PUBLIC_IMAGE_EXTENSIONS.has(path.extname(filePath).toLowerCase())) return null;
  try {
    const realRoot = fs.realpathSync(renderedRoot);
    const realFile = fs.realpathSync(filePath);
    const realRelative = path.relative(realRoot, realFile);
    if (!realRelative || realRelative.startsWith(`..${path.sep}`) || path.isAbsolute(realRelative)) return null;
    if (!fs.statSync(realFile).isFile()) return null;
    return realFile;
  } catch {
    return null;
  }
}

function presentationContentType(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  if (ext === '.png') return 'image/png';
  if (ext === '.jpg' || ext === '.jpeg') return 'image/jpeg';
  if (ext === '.webp') return 'image/webp';
  if (ext === '.svg') return 'image/svg+xml';
  return 'application/octet-stream';
}

module.exports = {
  PRESENTATION_DIR,
  loadPresentationIndex,
  getLessonPresentationPlan,
  getPresentationSlide,
  resolvePresentationAsset,
  presentationContentType
};
