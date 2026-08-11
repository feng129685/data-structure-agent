package com.feng.dsagent.chat;

import java.time.Instant;
import java.util.List;

public record ChatSessionView(
    String id,
    String chapterId,
    String title,
    Instant updatedAt,
    List<ChatMessageView> messages
) {

    public ChatSessionView {
        messages = List.copyOf(messages);
    }
}
