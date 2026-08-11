package com.feng.dsagent.chat;

import java.util.List;
import java.util.Optional;

interface ChatRepository {

    boolean isPublishedChapter(String chapterId);

    Optional<List<ChatTurn>> recentHistory(long userId, String sessionId, int limit);

    String saveExchange(
        long userId,
        String sessionId,
        String chapterId,
        String prompt,
        String answer,
        List<ChatSource> sources
    );
}
