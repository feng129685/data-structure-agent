package com.feng.dsagent.chat;

import java.time.Instant;
import java.util.List;

public record ChatMessageView(
    long id,
    String role,
    String content,
    List<ChatSource> sources,
    Instant createdAt
) {

    public ChatMessageView {
        sources = List.copyOf(sources);
    }
}
