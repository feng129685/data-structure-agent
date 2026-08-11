package com.feng.dsagent.learning;

import tools.jackson.databind.JsonNode;

public record LearningEventCommand(
    String eventType,
    String chapterId,
    String referenceId,
    JsonNode payload
) {
}
