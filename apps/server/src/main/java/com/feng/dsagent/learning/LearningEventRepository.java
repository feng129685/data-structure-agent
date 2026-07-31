package com.feng.dsagent.learning;

import java.time.Instant;

public interface LearningEventRepository {

    boolean isPublishedChapter(String chapterId);

    LearningEventView save(
        long userId,
        String eventType,
        String chapterId,
        String referenceId,
        String payloadJson,
        Instant createdAt
    );
}
