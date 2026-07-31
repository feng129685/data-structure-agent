package com.feng.dsagent.learning;

import java.time.Instant;

public record ChapterLearningProgress(
    String chapterId,
    int chapterNumber,
    String title,
    long chatCount,
    long classroomCount,
    long animationCount,
    long codeRunCount,
    long eventCount,
    long totalActivities,
    Instant lastActivityAt
) {
}
