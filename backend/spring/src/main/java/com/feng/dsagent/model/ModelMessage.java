package com.feng.dsagent.model;

import java.util.Objects;

public record ModelMessage(String role, String content) {

    public ModelMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
