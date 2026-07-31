package com.feng.dsagent.chat;

import java.util.List;

public record ChatResponse(String answer, String sessionId, List<ChatSource> sources, boolean persisted) {

    public ChatResponse {
        sources = List.copyOf(sources);
    }
}
