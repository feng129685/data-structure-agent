package com.feng.dsagent.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class KnowledgeSearchService {

    private static final Pattern INJECTION_PATTERN = Pattern.compile(
        "忽略(以上|之前|所有).{0,12}(指令|规则)|系统提示词|开发者消息|泄露.{0,8}提示词|执行以下命令|system\\s*prompt",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> DOMAIN_TERMS = Set.of(
        "数据结构", "算法", "复杂度", "线性表", "顺序表", "链表", "单链表", "双向链表",
        "栈", "队列", "二叉树", "树", "遍历", "前序", "中序", "后序", "层序", "图",
        "查找", "排序", "哈希", "堆", "指针", "结点", "节点", "入栈", "出栈", "头插", "尾插"
    );

    private final AtomicReference<List<KnowledgeChunk>> chunks;
    private final double minimumScore;

    public KnowledgeSearchService(Collection<KnowledgeChunk> chunks, double minimumScore) {
        this.chunks = new AtomicReference<>(List.copyOf(chunks));
        this.minimumScore = minimumScore;
    }

    public void replace(Collection<KnowledgeChunk> replacement) {
        chunks.set(List.copyOf(replacement));
    }

    public List<KnowledgeSearchResult> search(
        String query,
        String chapterId,
        int limit,
        KnowledgeAudience audience
    ) {
        if (query == null || query.isBlank() || limit < 1 || INJECTION_PATTERN.matcher(query).find()) {
            return List.of();
        }

        Set<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        Map<String, KnowledgeSearchResult> uniqueBySource = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks.get()) {
            if (audience == null || !audience.allows(chunk.licenseScope())) {
                continue;
            }
            if (chapterId != null && !chapterId.isBlank() && !chapterId.equals(chunk.chapterId())) {
                continue;
            }
            double score = score(chunk, queryTokens);
            if (score < minimumScore) {
                continue;
            }
            KnowledgeSearchResult candidate = new KnowledgeSearchResult(chunk, score);
            String sourceKey = chunk.source() == null ? chunk.id() : chunk.source();
            uniqueBySource.merge(sourceKey, candidate, (left, right) -> left.score() >= right.score() ? left : right);
        }

        return uniqueBySource.values().stream()
            .sorted(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed())
            .limit(Math.min(limit, 6))
            .toList();
    }

    private double score(KnowledgeChunk chunk, Set<String> queryTokens) {
        String normalizedTitle = normalize(chunk.title());
        String normalizedContent = normalize(chunk.content());
        double score = 0;
        for (String token : queryTokens) {
            if (normalizedTitle.contains(token)) {
                score += token.length() >= 3 ? 5 : 3;
            }
            if (normalizedContent.contains(token)) {
                score += token.length() >= 3 ? 2.5 : 1.5;
            }
        }
        return score;
    }

    private Set<String> tokens(String text) {
        String normalized = normalize(text);
        Set<String> result = new LinkedHashSet<>();
        for (String term : DOMAIN_TERMS) {
            if (normalized.contains(term)) {
                result.add(term);
            }
        }

        for (String part : NON_WORD.split(normalized)) {
            if (part.isBlank()) {
                continue;
            }
            if (part.matches("[a-z0-9]+")) {
                if (part.length() >= 2) {
                    result.add(part);
                }
                continue;
            }
            for (int size = 2; size <= 4; size++) {
                for (int index = 0; index + size <= part.length(); index++) {
                    result.add(part.substring(index, index + size));
                }
            }
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
