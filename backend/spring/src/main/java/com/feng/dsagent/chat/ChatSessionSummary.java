package com.feng.dsagent.chat;

import java.time.Instant;

public record ChatSessionSummary(
    String id,
    String chapterId,
    String title,
    Instant updatedAt,
    long messageCount
) {
}
