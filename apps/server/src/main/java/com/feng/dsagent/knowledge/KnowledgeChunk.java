package com.feng.dsagent.knowledge;

public record KnowledgeChunk(
    String id,
    String chapterId,
    String title,
    String content,
    String source,
    String pageLabel,
    String licenseScope
) {
}
