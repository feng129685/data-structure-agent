package com.feng.dsagent.learning;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LearningProgressService {

    private final LearningProgressRepository repository;

    LearningProgressService(LearningProgressRepository repository) {
        this.repository = repository;
    }

    public LearningProgressView progress(long userId) {
        List<ChapterLearningProgress> chapters = repository.progress(userId);
        long total = chapters.stream().mapToLong(ChapterLearningProgress::totalActivities).sum();
        return new LearningProgressView(total, chapters);
    }
}
