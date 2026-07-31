package com.feng.dsagent.chat;

import java.util.List;

public record ChatCommand(String prompt, String chapterId, String sessionId, List<ChatTurn> history) {

    public ChatCommand {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
