package com.feng.dsagent.chat;

public record ChatSource(
    String id,
    String chapterId,
    String title,
    String content,
    String source,
    String pageLabel,
    double score
) {
}
