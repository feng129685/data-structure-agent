package com.feng.dsagent.animation;

import java.util.Objects;

public record AnimationValidationError(String path, String code, String message) {

    public AnimationValidationError {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
