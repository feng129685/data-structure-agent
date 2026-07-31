const fs = require("fs");
const path = require("path");

const CJK_RANGE = "\\u3400-\\u9fff";
const QUESTION_STOP_TOKENS = new Set([
  "什么", "怎么", "怎样", "为什么", "为何", "如何", "是否", "可以", "需要",
  "这个", "那个", "哪些", "一种", "一下", "请问", "解释", "说明"
]);
const QUESTION_FILLER_PATTERN = /(?:用什么数据结构|数据结构|为什么|怎么样|是什么|有什么|用什么|请问|能否|是否|为何|如何|怎么|哪些|可以|需要|解释|说明|请)/g;
const INSTRUCTION_INJECTION_PATTERNS = [
  /忽略.{0,12}(?:前面|以上|系统|指令|要求)/i,
  /(?:输出|泄露|显示).{0,10}(?:系统提示|提示词|系统指令)/i,
  /(?:ignore|reveal|show).{0,20}(?:system prompt|developer message|previous instructions)/i
];

const KIND_WEIGHTS = {
  summary: 1.15,
  example: 1.2,
  exercise: 1.15,
  answer: 1.25,
  ocr: 1
};

function normalizeOcrText(value) {
  let text = String(value || "").normalize("NFKC").replace(/\r/g, "");
  text = text.replace(new RegExp(`([${CJK_RANGE}])\\s+(?=[${CJK_RANGE}])`, "g"), "$1");
  text = text
    .replace(/^>\s*OCR质量:[^\n]*$/gm, "")
    .replace(/^#+\s*/gm, "")
    .replace(/[ \t]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  return text;
}

function normalizeSearchText(value) {
  return normalizeOcrText(value)
    .toLowerCase()
    .replace(/[^a-z0-9_+#.\u3400-\u9fff]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function tokenizeQuery(value) {
  const normalized = normalizeSearchText(value);
  const tokens = new Set();

  for (const word of normalized.match(/[a-z0-9_+#.]+/g) || []) {
    if (word.length >= 2 || /^(c|b)$/.test(word)) tokens.add(word);
  }

  for (const rawRun of normalized.match(/[\u3400-\u9fff]+/g) || []) {
    const runs = rawRun.replace(QUESTION_FILLER_PATTERN, " ").split(/\s+/).filter(Boolean);
    for (const run of runs) {
      if (run.length === 1) tokens.add(run);
      if (run.length >= 2 && run.length <= 8 && !QUESTION_STOP_TOKENS.has(run)) tokens.add(run);
      for (let size = 2; size <= 3; size += 1) {
        for (let index = 0; index <= run.length - size; index += 1) {
          const token = run.slice(index, index + size);
          if (!QUESTION_STOP_TOKENS.has(token)) tokens.add(token);
        }
      }
    }
  }

  return [...tokens];
}

function splitLongText(value, maxChars = 1100, overlap = 100) {
  const text = normalizeOcrText(value);
  if (!text) return [];

  const paragraphs = text.split(/\n{2,}/).map((item) => item.trim()).filter(Boolean);
  const chunks = [];
  let current = "";

  function pushCurrent() {
    if (!current.trim()) return;
    chunks.push(current.trim());
    current = "";
  }

  for (const paragraph of paragraphs) {
    if (paragraph.length > maxChars) {
      pushCurrent();
      let start = 0;
      while (start < paragraph.length) {
        const end = Math.min(paragraph.length, start + maxChars);
        chunks.push(paragraph.slice(start, end).trim());
        if (end >= paragraph.length) break;
        start = Math.max(start + 1, end - overlap);
      }
      continue;
    }

    const candidate = current ? `${current}\n\n${paragraph}` : paragraph;
    if (candidate.length > maxChars) pushCurrent();
    current = current ? `${current}\n\n${paragraph}` : paragraph;
  }

  pushCurrent();
  return chunks;
}

function extractLessonSections(text) {
  const matches = [...String(text || "").matchAll(/^##\s+(\d+)\.\s+([^\n]+)$/gm)];
  const sections = new Map();
  matches.forEach((match, index) => {
    const start = match.index + match[0].length;
    const end = index + 1 < matches.length ? matches[index + 1].index : text.length;
    sections.set(match[1], {
      heading: match[2].trim(),
      body: text.slice(start, end).trim()
    });
  });
  return sections;
}

function parseLessonIdentity(text, filename) {
  const number = String(text || "").match(/^#\s*课时编号[:：]\s*([^\n]+)$/m)?.[1]?.trim()
    || filename.match(/^(\d{2}-\d{2})/)?.[1]
    || "";
  const title = String(text || "").match(/^#\s*课时标题[:：]\s*([^\n]+)$/m)?.[1]?.trim()
    || filename.replace(/\.md$/i, "");
  return { number, title };
}

function stripSubheadings(value) {
  return String(value || "")
    .replace(/^###\s+[^\n]+$/gm, "")
    .replace(/^[-*]\s+/gm, "")
    .trim();
}

const KNOWLEDGE_KIND_LABELS = Object.freeze({
  summary: "课程知识整理",
  ocr: "教材 OCR",
  example: "例题整理",
  exercise: "习题整理",
  answer: "参考答案"
});

function buildKnowledgeMetadata(chunk) {
  const kindLabel = KNOWLEDGE_KIND_LABELS[chunk.kind] || "课程资料";
  const sourceLabel = chunk.kind === "answer"
    ? chunk.title
    : [chunk.lessonNumber ? `课时 ${chunk.lessonNumber}` : "", chunk.title].filter(Boolean).join(" · ");
  const reviewStatus = chunk.kind === "ocr"
    ? "OCR 待人工复核"
    : chunk.kind === "answer"
      ? "答案版本待人工核对"
      : "课程整理稿";
  return {
    sourceLabel: sourceLabel || chunk.title || "课程资料",
    locationLabel: [kindLabel, chunk.pageLabel].filter(Boolean).join(" · "),
    reviewStatus
  };
}

function buildLessonChunks(entry, rootDir, maxChunkChars) {
  const text = fs.readFileSync(entry, "utf8");
  const filename = path.basename(entry);
  const source = path.relative(rootDir, entry).replace(/\\/g, "/");
  const identity = parseLessonIdentity(text, filename);
  const sections = extractLessonSections(text);
  const chunks = [];

  function append(kind, body, pageLabel = "") {
    splitLongText(body, maxChunkChars).forEach((content, index) => {
      if (!content) return;
      chunks.push({
        id: `${source}:${kind}:${pageLabel || index + 1}`,
        title: identity.title,
        lessonNumber: identity.number,
        kind,
        source,
        pageLabel,
        content
      });
    });
  }

  const summaryParts = [sections.get("2")?.body, sections.get("6")?.body]
    .map(stripSubheadings)
    .filter(Boolean);
  const mistakes = stripSubheadings(sections.get("7")?.body || "");
  if (mistakes && !mistakes.includes("无独立扩展")) summaryParts.push(mistakes);
  append("summary", summaryParts.join("\n\n"));

  const ocrBody = sections.get("3")?.body || "";
  const pageMatches = [...ocrBody.matchAll(/^###\s+教材页\s*([^\n]+)$/gm)];
  if (pageMatches.length) {
    pageMatches.forEach((match, index) => {
      const start = match.index + match[0].length;
      const end = index + 1 < pageMatches.length ? pageMatches[index + 1].index : ocrBody.length;
      append("ocr", ocrBody.slice(start, end), `教材页 ${match[1].trim()}`);
    });
  } else {
    append("ocr", ocrBody);
  }

  append("example", sections.get("4")?.body || "");

  const exercise = sections.get("8")?.body || "";
  if (exercise && !/本课时不是独立习题课[^\n]*未全文摘入参考答案/.test(exercise)) {
    append("exercise", exercise);
  }

  return chunks;
}

function buildAnswerChunks(answerPath, rootDir, maxChunkChars) {
  if (!fs.existsSync(answerPath)) return { chapterCount: 0, chunks: [] };
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(answerPath, "utf8"));
  } catch {
    return { chapterCount: 0, chunks: [] };
  }

  const chunks = [];
  const entries = Object.entries(parsed || {});
  for (const [chapter, value] of entries) {
    const text = value && typeof value === "object" ? value.text : "";
    const pageLabel = value && typeof value === "object" && value.start_page
      ? `参考答案第 ${value.start_page}-${value.end_page || value.start_page} 页`
      : "";
    splitLongText(text, maxChunkChars).forEach((content, index) => {
      chunks.push({
        id: `raw/answer_by_chapter.json:${chapter}:${index + 1}`,
        title: `第 ${chapter} 章习题参考答案`,
        lessonNumber: `${String(chapter).padStart(2, "0")}-answer`,
        kind: "answer",
        source: path.relative(rootDir, answerPath).replace(/\\/g, "/"),
        pageLabel,
        content
      });
    });
  }
  return { chapterCount: entries.length, chunks };
}

function countOccurrences(text, token) {
  if (!text || !token) return 0;
  let count = 0;
  let start = 0;
  while (count < 4) {
    const index = text.indexOf(token, start);
    if (index < 0) break;
    count += 1;
    start = index + token.length;
  }
  return count;
}

function createExcerpt(content, tokens, maxChars = 360) {
  const text = normalizeOcrText(content).replace(/\n+/g, " ");
  if (text.length <= maxChars) return text;
  const positions = tokens
    .map((token) => text.toLowerCase().indexOf(token))
    .filter((index) => index >= 0);
  const hit = positions.length ? Math.min(...positions) : 0;
  const start = Math.max(0, hit - Math.floor(maxChars * 0.25));
  const end = Math.min(text.length, start + maxChars);
  return `${start > 0 ? "..." : ""}${text.slice(start, end).trim()}${end < text.length ? "..." : ""}`;
}

function createKnowledgeRetriever(options = {}) {
  const rootDir = path.resolve(options.rootDir || "");
  const maxChunkChars = Math.max(500, Number(options.maxChunkChars || 1100));
  const defaultMinScore = Math.max(0, Number(options.minScore ?? 8));
  let chunks = [];
  let stats = {
    ready: false,
    rootDir,
    lessonCount: 0,
    answerChapterCount: 0,
    chunkCount: 0,
    loadedAt: null
  };

  function load() {
    chunks = [];
    if (!rootDir || !fs.existsSync(rootDir)) {
      stats = { ...stats, ready: false, lessonCount: 0, answerChapterCount: 0, chunkCount: 0, loadedAt: new Date().toISOString() };
      return { ...stats };
    }

    const lessonDir = path.join(rootDir, "lessons");
    const lessonFiles = fs.existsSync(lessonDir)
      ? fs.readdirSync(lessonDir)
          .filter((name) => name.endsWith(".md") && name !== "00-lesson-index.md")
          .sort()
          .map((name) => path.join(lessonDir, name))
      : [];

    for (const lessonFile of lessonFiles) {
      try {
        chunks.push(...buildLessonChunks(lessonFile, rootDir, maxChunkChars));
      } catch (error) {
        console.warn(`Knowledge lesson skipped (${path.basename(lessonFile)}): ${error.message}`);
      }
    }

    const answers = buildAnswerChunks(path.join(rootDir, "raw", "answer_by_chapter.json"), rootDir, maxChunkChars);
    chunks.push(...answers.chunks);
    chunks = chunks.map((chunk) => ({
      ...chunk,
      normalizedTitle: normalizeSearchText(chunk.title),
      normalizedContent: normalizeSearchText(chunk.content)
    }));

    stats = {
      ready: chunks.length > 0,
      rootDir,
      lessonCount: lessonFiles.length,
      answerChapterCount: answers.chapterCount,
      chunkCount: chunks.length,
      loadedAt: new Date().toISOString()
    };
    return { ...stats };
  }

  function search(query, searchOptions = {}) {
    if (!chunks.length || !String(query || "").trim()) return [];
    if (INSTRUCTION_INJECTION_PATTERNS.some((pattern) => pattern.test(String(query)))) return [];
    const limit = Math.max(1, Math.min(8, Number(searchOptions.limit || 4)));
    const minScore = Math.max(0, Number(searchOptions.minScore ?? defaultMinScore));
    const tokens = tokenizeQuery(query);
    if (!tokens.length) return [];
    const scenarioTokens = tokenizeQuery(searchOptions.scenario || "");

    const candidates = chunks
      .map((chunk) => {
        let score = 0;
        let contentScore = 0;
        const matchedTokens = new Set();
        for (const token of tokens) {
          const titleHits = countOccurrences(chunk.normalizedTitle, token);
          const contentHits = countOccurrences(chunk.normalizedContent, token);
          if (titleHits || contentHits) matchedTokens.add(token);
          const lengthBoost = token.length >= 3 ? 1.45 : 1;
          score += titleHits * 8 * lengthBoost;
          const contentContribution = Math.min(contentHits, 3) * 1.8 * lengthBoost;
          contentScore += contentContribution;
          score += contentContribution;
        }
        for (const token of scenarioTokens) {
          if (chunk.normalizedTitle.includes(token)) score += 3;
        }
        score *= KIND_WEIGHTS[chunk.kind] || 1;
        contentScore *= KIND_WEIGHTS[chunk.kind] || 1;
        return { chunk, score, contentScore, matchedTokenCount: matchedTokens.size };
      })
      .filter((item) => {
        const requiredMatches = tokens.length >= 4 ? 2 : 1;
        return item.score >= minScore && item.matchedTokenCount >= requiredMatches;
      })
      .sort((a, b) => b.score - a.score || a.chunk.source.localeCompare(b.chunk.source, "zh-CN"));

    const selected = [];
    const seenSources = new Set();
    const bestContentBySource = new Map();
    const contentKindPriority = { ocr: 4, example: 3, exercise: 2, answer: 2, summary: 1 };
    for (const candidate of candidates) {
      const current = bestContentBySource.get(candidate.chunk.source);
      const hasMoreMatches = current && candidate.matchedTokenCount > current.matchedTokenCount;
      const hasBetterContent = current
        && candidate.matchedTokenCount === current.matchedTokenCount
        && candidate.contentScore > current.contentScore;
      const hasBetterKind = current
        && candidate.matchedTokenCount === current.matchedTokenCount
        && candidate.contentScore === current.contentScore
        && (contentKindPriority[candidate.chunk.kind] || 0) > (contentKindPriority[current.chunk.kind] || 0);
      if (!current || hasMoreMatches || hasBetterContent || hasBetterKind) {
        bestContentBySource.set(candidate.chunk.source, candidate);
      }
    }
    for (const candidate of candidates) {
      if (seenSources.has(candidate.chunk.source)) continue;
      seenSources.add(candidate.chunk.source);
      const bestContent = bestContentBySource.get(candidate.chunk.source) || candidate;
      selected.push({ ...bestContent, score: candidate.score });
      if (selected.length >= limit) break;
    }

    return selected.map(({ chunk, score }) => ({
        title: chunk.title,
        lessonNumber: chunk.lessonNumber,
        kind: chunk.kind,
        source: chunk.source,
        pageLabel: chunk.pageLabel,
        ...buildKnowledgeMetadata(chunk),
        excerpt: createExcerpt(chunk.content, tokens),
        score: Math.round(score * 100) / 100
      }));
  }

  return {
    load,
    reload: load,
    search,
    getStats: () => ({ ...stats })
  };
}

function formatKnowledgeContext(results, options = {}) {
  if (!Array.isArray(results) || results.length === 0) return "";
  const maxChars = Math.max(300, Number(options.maxChars || 3600));
  const lines = [
    "课程教材检索结果（资料来自 OCR 和参考答案，可能存在识别误差；回答时应综合判断，不要照抄明显乱码）："
  ];

  for (const [index, item] of results.entries()) {
    const meta = [item.sourceLabel || item.title, item.locationLabel, item.reviewStatus].filter(Boolean).join(" · ");
    const block = `\n[${index + 1}] ${meta}\n${item.excerpt}`;
    const candidate = `${lines.join("\n")}${block}`;
    if (candidate.length > maxChars) {
      const remaining = maxChars - lines.join("\n").length - 1;
      if (remaining > 80) lines.push(block.slice(1, remaining));
      break;
    }
    lines.push(block.slice(1));
  }

  return lines.join("\n").slice(0, maxChars);
}

module.exports = {
  createKnowledgeRetriever,
  formatKnowledgeContext,
  normalizeOcrText,
  tokenizeQuery
};
