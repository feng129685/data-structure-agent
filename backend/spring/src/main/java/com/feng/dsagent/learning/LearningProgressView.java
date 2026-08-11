package com.feng.dsagent.learning;

import java.util.List;

public record LearningProgressView(long totalActivities, List<ChapterLearningProgress> chapters) {

    public LearningProgressView {
        chapters = List.copyOf(chapters);
    }
}
