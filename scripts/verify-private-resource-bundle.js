"use strict";

// Validates mounted courseware without emitting file names, hashes, or content.
const fs = require("node:fs");
const path = require("node:path");

const WORKSPACE_ROOT = path.resolve(__dirname, "..");
const PRIVATE_ROOT = path.resolve(process.env.STRUCTIFY_PRIVATE_ROOT || path.join(WORKSPACE_ROOT, "private"));
const REQUIRE_PRIVATE_RESOURCES = process.env.STRUCTIFY_REQUIRE_PRIVATE_RESOURCES === "true";
const IMAGE_EXTENSIONS = new Set([".png", ".jpg", ".jpeg", ".webp"]);

class ValidationState {
  constructor() {
    this.failures = new Set();
    this.metrics = {
      presentationSlides: 0,
      presentationPlans: 0,
      presentationImageReferences: 0,
      curriculumChapters: 0,
      curriculumLessons: 0,
      pdfs: 0,
      sourcePptx: 0
    };
  }

  fail(code) {
    this.failures.add(code);
  }
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isNonEmptyString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function isPositiveInteger(value) {
  return Number.isInteger(value) && value > 0;
}

function exists(target) {
  try {
    fs.accessSync(target, fs.constants.F_OK);
    return true;
  } catch {
    return false;
  }
}

function isDescendant(base, candidate) {
  const relative = path.relative(base, candidate);
  return Boolean(relative) && relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}

function safeRelativePath(value) {
  if (!isNonEmptyString(value)) return null;
  const normalized = value.trim().replace(/\\/g, "/");
  if (
    normalized.includes("\0") ||
    path.posix.isAbsolute(normalized) ||
    path.win32.isAbsolute(normalized) ||
    /^[a-zA-Z]:/.test(normalized)
  ) return null;

  const segments = normalized.split("/");
  if (segments.some((segment) => !segment || segment === "." || segment === "..")) return null;
  return segments.join(path.sep);
}

function resolveInside(root, relativePath) {
  const normalized = safeRelativePath(relativePath);
  if (!normalized) return null;
  const resolved = path.resolve(root, normalized);
  return isDescendant(root, resolved) ? resolved : null;
}

function inspectRegularFile(root, filePath) {
  try {
    const stat = fs.lstatSync(filePath);
    if (!stat.isFile() || stat.size <= 0) return null;
    const realRoot = fs.realpathSync(root);
    const realFile = fs.realpathSync(filePath);
    if (!isDescendant(realRoot, realFile)) return null;
    return { size: stat.size };
  } catch {
    return null;
  }
}

function readJson(root, filePath, state, failureCode) {
  if (!inspectRegularFile(root, filePath)) {
    state.fail(failureCode);
    return null;
  }

  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch {
    state.fail(failureCode);
    return null;
  }
}

function readPrefix(filePath, byteLength) {
  const descriptor = fs.openSync(filePath, "r");
  try {
    const buffer = Buffer.alloc(byteLength);
    const bytesRead = fs.readSync(descriptor, buffer, 0, byteLength, 0);
    return buffer.subarray(0, bytesRead);
  } finally {
    fs.closeSync(descriptor);
  }
}

function collectRegularFiles(root, predicate, state, failureCode) {
  const files = [];

  function visit(directory) {
    let entries;
    try {
      entries = fs.readdirSync(directory, { withFileTypes: true });
    } catch {
      state.fail(failureCode);
      return;
    }

    for (const entry of entries) {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(target);
      } else if (entry.isFile() && predicate(target)) {
        files.push(target);
      }
    }
  }

  if (!exists(root)) {
    state.fail(failureCode);
    return files;
  }
  visit(root);
  return files;
}

function addSlideReference(slideId, slideIds, state) {
  if (!isNonEmptyString(slideId) || !slideIds.has(slideId)) {
    state.fail("PRESENTATION_PLAN_SLIDE_REFERENCE_INVALID");
    return;
  }
  state.metrics.presentationImageReferences += 1;
}

function validatePresentation(state) {
  const presentationRoot = path.join(PRIVATE_ROOT, "presentation-materials");
  const renderedRoot = path.join(presentationRoot, "rendered");
  const slidesDocument = readJson(
    presentationRoot,
    path.join(presentationRoot, "slides.json"),
    state,
    "PRESENTATION_SLIDES_MANIFEST_INVALID"
  );
  const plansDocument = readJson(
    presentationRoot,
    path.join(presentationRoot, "lesson-presentation-plans.json"),
    state,
    "PRESENTATION_PLANS_MANIFEST_INVALID"
  );

  const slideIds = new Set();
  if (isPlainObject(slidesDocument) && Array.isArray(slidesDocument.slides)) {
    const slides = slidesDocument.slides;
    state.metrics.presentationSlides = slides.length;
    if (!slides.length) state.fail("PRESENTATION_SLIDES_EMPTY");
    if (Object.hasOwn(slidesDocument, "slideCount") && slidesDocument.slideCount !== slides.length) {
      state.fail("PRESENTATION_SLIDE_COUNT_MISMATCH");
    }

    for (const slide of slides) {
      if (!isPlainObject(slide) || !isNonEmptyString(slide.id)) {
        state.fail("PRESENTATION_SLIDE_SCHEMA_INVALID");
        continue;
      }
      if (slideIds.has(slide.id)) state.fail("PRESENTATION_SLIDE_ID_DUPLICATE");
      slideIds.add(slide.id);

      const relativeImage = safeRelativePath(slide.imagePath);
      if (!relativeImage || !relativeImage.startsWith(`rendered${path.sep}`)) {
        state.fail("PRESENTATION_IMAGE_REFERENCE_INVALID");
        continue;
      }
      const imagePath = resolveInside(presentationRoot, slide.imagePath);
      if (!imagePath || !isDescendant(renderedRoot, imagePath)) {
        state.fail("PRESENTATION_IMAGE_REFERENCE_INVALID");
        continue;
      }
      if (!IMAGE_EXTENSIONS.has(path.extname(imagePath).toLowerCase())) {
        state.fail("PRESENTATION_IMAGE_EXTENSION_INVALID");
        continue;
      }
      if (!inspectRegularFile(renderedRoot, imagePath)) {
        state.fail("PRESENTATION_IMAGE_MISSING_OR_UNSAFE");
      }
    }
  } else if (slidesDocument !== null) {
    state.fail("PRESENTATION_SLIDES_MANIFEST_INVALID");
  }

  if (!isPlainObject(plansDocument) || !isPlainObject(plansDocument.lessons)) {
    if (plansDocument !== null) state.fail("PRESENTATION_PLANS_MANIFEST_INVALID");
    return;
  }

  const plans = Object.entries(plansDocument.lessons);
  state.metrics.presentationPlans = plans.length;
  if (!plans.length) state.fail("PRESENTATION_PLANS_EMPTY");

  for (const [lessonId, plan] of plans) {
    if (!isPlainObject(plan)) {
      state.fail("PRESENTATION_PLAN_SCHEMA_INVALID");
      continue;
    }
    if (!isNonEmptyString(plan.lessonId) || plan.lessonId !== lessonId || !isNonEmptyString(plan.title)) {
      state.fail("PRESENTATION_PLAN_SCHEMA_INVALID");
    }
    if (!Array.isArray(plan.slideOrder) || !plan.slideOrder.length) {
      state.fail("PRESENTATION_PLAN_SLIDE_ORDER_INVALID");
    } else {
      for (const slideId of plan.slideOrder) addSlideReference(slideId, slideIds, state);
    }
    if (!isPlainObject(plan.scenes) || !Object.keys(plan.scenes).length) {
      state.fail("PRESENTATION_PLAN_SCENES_INVALID");
      continue;
    }
    for (const scene of Object.values(plan.scenes)) {
      if (!isPlainObject(scene) || !Array.isArray(scene.slides) || !isNonEmptyString(scene.primarySlideId)) {
        state.fail("PRESENTATION_PLAN_SCENES_INVALID");
        continue;
      }
      for (const slideId of scene.slides) addSlideReference(slideId, slideIds, state);
      addSlideReference(scene.primarySlideId, slideIds, state);
    }
  }
}

function validatePageRange(value) {
  return Array.isArray(value) && value.length > 0 && value.every(isPositiveInteger);
}

function chapterIdFor(lessonId) {
  return isNonEmptyString(lessonId) ? lessonId.split("-", 1)[0] : "";
}

function validateCurriculum(state) {
  const knowledgeRoot = path.join(PRIVATE_ROOT, "knowledge");
  const curriculum = readJson(
    knowledgeRoot,
    path.join(knowledgeRoot, "curriculum.json"),
    state,
    "CURRICULUM_MANIFEST_INVALID"
  );
  if (!isPlainObject(curriculum) || !Array.isArray(curriculum.chapters) || !Array.isArray(curriculum.lessons)) {
    if (curriculum !== null) state.fail("CURRICULUM_MANIFEST_INVALID");
    return;
  }

  state.metrics.curriculumChapters = curriculum.chapters.length;
  state.metrics.curriculumLessons = curriculum.lessons.length;
  if (curriculum.chapters.length !== 10) state.fail("CURRICULUM_CHAPTER_COUNT_INVALID");
  if (curriculum.lessons.length !== 95) state.fail("CURRICULUM_LESSON_COUNT_INVALID");

  const chapterIds = new Set();
  for (const chapter of curriculum.chapters) {
    if (!isPlainObject(chapter) || !isNonEmptyString(chapter.id) || !isNonEmptyString(chapter.title)) {
      state.fail("CURRICULUM_CHAPTER_SCHEMA_INVALID");
      continue;
    }
    if (chapterIds.has(chapter.id)) state.fail("CURRICULUM_CHAPTER_ID_DUPLICATE");
    chapterIds.add(chapter.id);
  }

  const lessonIds = new Set();
  for (const lesson of curriculum.lessons) {
    if (!isPlainObject(lesson) || !isNonEmptyString(lesson.id)) {
      state.fail("CURRICULUM_LESSON_SCHEMA_INVALID");
      continue;
    }
    if (lessonIds.has(lesson.id)) state.fail("CURRICULUM_LESSON_ID_DUPLICATE");
    lessonIds.add(lesson.id);

    if (
      !isNonEmptyString(lesson.title) ||
      !isNonEmptyString(lesson.lessonType) ||
      !isNonEmptyString(lesson.section) ||
      !isNonEmptyString(lesson.sourceLessonId) ||
      !isPositiveInteger(lesson.minutes) ||
      !validatePageRange(lesson.textbookPages) ||
      !validatePageRange(lesson.pdfPages)
    ) {
      state.fail("CURRICULUM_LESSON_SCHEMA_INVALID");
    }
    if (!chapterIds.has(chapterIdFor(lesson.id)) || !chapterIds.has(chapterIdFor(lesson.sourceLessonId))) {
      state.fail("CURRICULUM_LESSON_CHAPTER_REFERENCE_INVALID");
    }
  }
}

function isPdfSignature(prefix) {
  return prefix.length >= 5 && prefix.subarray(0, 5).equals(Buffer.from("%PDF-"));
}

function validatePdfs(state) {
  const pdfRoot = path.join(PRIVATE_ROOT, "pdfs");
  const manifest = readJson(
    pdfRoot,
    path.join(pdfRoot, "materials-manifest.json"),
    state,
    "PDF_MANIFEST_INVALID"
  );
  if (!Array.isArray(manifest)) {
    if (manifest !== null) state.fail("PDF_MANIFEST_INVALID");
    return;
  }

  state.metrics.pdfs = manifest.length;
  if (!manifest.length) state.fail("PDF_MANIFEST_EMPTY");
  const manifestFiles = new Set();
  for (const material of manifest) {
    if (
      !isPlainObject(material) ||
      !isNonEmptyString(material.filename) ||
      !isPositiveInteger(material.pages) ||
      !(Number.isFinite(material.sizeKB) && material.sizeKB > 0)
    ) {
      state.fail("PDF_MANIFEST_ENTRY_INVALID");
      continue;
    }
    const relativeFile = safeRelativePath(material.filename);
    const pdfPath = relativeFile ? resolveInside(pdfRoot, material.filename) : null;
    if (!relativeFile || !pdfPath || path.extname(pdfPath).toLowerCase() !== ".pdf") {
      state.fail("PDF_MANIFEST_ENTRY_INVALID");
      continue;
    }
    const normalizedRelativeFile = relativeFile.split(path.sep).join("/");
    if (manifestFiles.has(normalizedRelativeFile)) state.fail("PDF_MANIFEST_FILENAME_DUPLICATE");
    manifestFiles.add(normalizedRelativeFile);
    if (!inspectRegularFile(pdfRoot, pdfPath)) {
      state.fail("PDF_MISSING_OR_UNSAFE");
      continue;
    }
    try {
      if (!isPdfSignature(readPrefix(pdfPath, 5))) state.fail("PDF_SIGNATURE_INVALID");
    } catch {
      state.fail("PDF_SIGNATURE_INVALID");
    }
  }

  const diskPdfs = collectRegularFiles(
    pdfRoot,
    (filePath) => path.extname(filePath).toLowerCase() === ".pdf",
    state,
    "PDF_DIRECTORY_UNREADABLE"
  );
  for (const filePath of diskPdfs) {
    const relative = path.relative(pdfRoot, filePath).split(path.sep).join("/");
    if (!manifestFiles.has(relative)) state.fail("PDF_UNMANIFESTED_FILE_PRESENT");
  }
}

function hasZipSignature(prefix) {
  return (
    prefix.length >= 4 &&
    prefix[0] === 0x50 &&
    prefix[1] === 0x4b &&
    (prefix[2] === 0x03 || prefix[2] === 0x05 || prefix[2] === 0x07) &&
    (prefix[3] === 0x04 || prefix[3] === 0x06 || prefix[3] === 0x08)
  );
}

function isGitLfsPointer(prefix) {
  return prefix.toString("ascii").startsWith("version https://git-lfs.github.com/spec/v1");
}

function validateSourcePptx(state) {
  const sourceRoot = path.join(PRIVATE_ROOT, "source-ppt");
  const files = collectRegularFiles(
    sourceRoot,
    (filePath) => path.extname(filePath).toLowerCase() === ".pptx",
    state,
    "SOURCE_PPTX_DIRECTORY_UNREADABLE"
  );
  state.metrics.sourcePptx = files.length;
  if (!files.length) {
    state.fail("SOURCE_PPTX_EMPTY");
    return;
  }

  for (const filePath of files) {
    if (!inspectRegularFile(sourceRoot, filePath)) {
      state.fail("SOURCE_PPTX_MISSING_OR_UNSAFE");
      continue;
    }
    try {
      const prefix = readPrefix(filePath, 128);
      if (isGitLfsPointer(prefix)) state.fail("SOURCE_PPTX_GIT_LFS_POINTER");
      if (!hasZipSignature(prefix)) state.fail("SOURCE_PPTX_ZIP_SIGNATURE_INVALID");
    } catch {
      state.fail("SOURCE_PPTX_ZIP_SIGNATURE_INVALID");
    }
  }
}

function hasMountedBundle() {
  return ["presentation-materials", "knowledge", "pdfs", "source-ppt"]
    .some((directory) => exists(path.join(PRIVATE_ROOT, directory)));
}

function main() {
  if (!hasMountedBundle() && !REQUIRE_PRIVATE_RESOURCES) {
    console.log("PRIVATE_RESOURCE_BUNDLE_EXTERNAL_RESOURCES_REQUIRED");
    return;
  }

  const state = new ValidationState();
  validatePresentation(state);
  validateCurriculum(state);
  validatePdfs(state);
  validateSourcePptx(state);

  if (state.failures.size) {
    console.error(`PRIVATE_RESOURCE_BUNDLE_INVALID checks=${[...state.failures].sort().join(",")}`);
    process.exitCode = 1;
    return;
  }

  const metrics = state.metrics;
  console.log(
    "PRIVATE_RESOURCE_BUNDLE_OK" +
    ` presentationSlides=${metrics.presentationSlides}` +
    ` presentationPlans=${metrics.presentationPlans}` +
    ` presentationImageReferences=${metrics.presentationImageReferences}` +
    ` curriculumChapters=${metrics.curriculumChapters}` +
    ` curriculumLessons=${metrics.curriculumLessons}` +
    ` pdfs=${metrics.pdfs}` +
    ` sourcePptx=${metrics.sourcePptx}`
  );
}

try {
  main();
} catch {
  console.error("PRIVATE_RESOURCE_BUNDLE_INVALID checks=UNEXPECTED_VALIDATOR_ERROR");
  process.exitCode = 1;
}
