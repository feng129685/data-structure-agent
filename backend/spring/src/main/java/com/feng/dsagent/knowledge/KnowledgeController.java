package com.feng.dsagent.knowledge;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.AuthenticatedUser;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
public final class KnowledgeController {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_CHAPTER_LENGTH = 64;
    private static final int DEFAULT_LIMIT = 4;
    private static final int MAX_LIMIT = 6;
    private static final int MAX_EXCERPT_LENGTH = 360;

    private final KnowledgeSearchService search;

    public KnowledgeController(KnowledgeSearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    KnowledgeSearchResponse search(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String chapterId,
        @RequestParam(required = false, defaultValue = "4") String limit
    ) {
        String query = normalize(q);
        if (query.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "KNOWLEDGE_QUERY_REQUIRED", "请输入检索内容");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "KNOWLEDGE_QUERY_TOO_LONG", "检索内容过长");
        }
        String chapter = normalize(chapterId);
        if (chapter.length() > MAX_CHAPTER_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "KNOWLEDGE_CHAPTER_TOO_LONG", "章节编号无效");
        }
        int boundedLimit = parseLimit(limit);
        KnowledgeAudience audience = KnowledgeAudience.from(user);
        List<KnowledgeResultView> results = search.search(query, chapter, boundedLimit, audience).stream()
            .map(KnowledgeController::view)
            .toList();
        return new KnowledgeSearchResponse(true, query, results);
    }

    private static int parseLimit(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(value.trim())));
        } catch (NumberFormatException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "KNOWLEDGE_LIMIT_INVALID", "返回数量参数无效");
        }
    }

    private static KnowledgeResultView view(KnowledgeSearchResult result) {
        KnowledgeChunk chunk = result.chunk();
        String source = safeSource(chunk.source());
        String location = blankToDefault(chunk.pageLabel(), chunk.chapterId());
        String kind = source.toLowerCase(java.util.Locale.ROOT).contains("answer") ? "answer" : "textbook";
        return new KnowledgeResultView(
            chunk.id(),
            chunk.chapterId(),
            chunk.title(),
            chunk.chapterId(),
            kind,
            source,
            chunk.pageLabel(),
            source,
            location,
            "已审核",
            "PUBLISHED",
            truncate(chunk.content()),
            result.score()
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String truncate(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_EXCERPT_LENGTH
            ? compact
            : compact.substring(0, MAX_EXCERPT_LENGTH).trim() + "…";
    }

    private static String safeSource(String value) {
        String source = value == null ? "" : value.replace('\\', '/').trim();
        if (source.isBlank()) return "课程资料";
        if (source.contains("..") || source.startsWith("/") || source.matches("^[A-Za-z]:/.*")) {
            try {
                return Path.of(source).getFileName().toString();
            } catch (RuntimeException ignored) {
                return "课程资料";
            }
        }
        return source;
    }

    public record KnowledgeSearchResponse(boolean ok, String query, List<KnowledgeResultView> results) {
    }

    public record KnowledgeResultView(
        String id,
        String chapterId,
        String title,
        String lessonNumber,
        String kind,
        String source,
        String pageLabel,
        String sourceLabel,
        String locationLabel,
        String reviewStatus,
        String publicationStatus,
        String excerpt,
        double score
    ) {
    }
}
