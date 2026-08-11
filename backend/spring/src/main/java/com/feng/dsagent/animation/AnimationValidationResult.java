package com.feng.dsagent.animation;

import java.util.List;
import java.util.Objects;

public record AnimationValidationResult(boolean valid, List<AnimationValidationError> errors) {

    public AnimationValidationResult {
        Objects.requireNonNull(errors, "errors must not be null");
        errors = List.copyOf(errors);
        if (valid != errors.isEmpty()) {
            throw new IllegalArgumentException("valid must match whether errors is empty");
        }
    }

    public static AnimationValidationResult fromErrors(List<AnimationValidationError> errors) {
        return new AnimationValidationResult(errors.isEmpty(), errors);
    }
}
