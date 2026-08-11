package com.feng.dsagent.classroom;

import tools.jackson.databind.JsonNode;

public record ClassroomSessionView(
    String id,
    long userId,
    String scriptId,
    ClassroomState state,
    boolean paused,
    String summary,
    JsonNode stage,
    ClassroomAnswerEvaluation answerEvaluation
) {
}
