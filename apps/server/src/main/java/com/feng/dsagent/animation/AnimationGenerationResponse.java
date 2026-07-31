package com.feng.dsagent.animation;

public record AnimationGenerationResponse(
    AnimationDefinition definition,
    String recordId,
    boolean persisted
) {
}
