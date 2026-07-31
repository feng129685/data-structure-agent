package com.feng.dsagent.learning;

import java.util.List;

interface LearningProgressRepository {

    List<ChapterLearningProgress> progress(long userId);
}
