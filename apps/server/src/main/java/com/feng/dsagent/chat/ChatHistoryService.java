package com.feng.dsagent.chat;

import com.feng.dsagent.common.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ChatHistoryService {

    private final ChatHistoryRepository repository;

    ChatHistoryService(ChatHistoryRepository repository) {
        this.repository = repository;
    }

    public List<ChatSessionSummary> sessions(long userId) {
        return repository.findSessions(userId);
    }

    public ChatSessionView session(long userId, String sessionId) {
        return repository.findSession(userId, normalizedId(sessionId))
            .orElseThrow(this::notFound);
    }

    public boolean delete(long userId, String sessionId) {
        if (!repository.deleteSession(userId, normalizedId(sessionId))) {
            throw notFound();
        }
        return true;
    }

    private String normalizedId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw notFound();
        }
        return sessionId.trim();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CHAT_SESSION_NOT_FOUND", "对话会话不存在");
    }
}
