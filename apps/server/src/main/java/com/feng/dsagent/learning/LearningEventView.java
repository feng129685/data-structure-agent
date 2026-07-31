package com.feng.dsagent.learning;

import java.time.Instant;

public record LearningEventView(
    long id,
    String eventType,
    String chapterId,
    String referenceId,
    Instant createdAt
) {
}
