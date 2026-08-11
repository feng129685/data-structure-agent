package com.feng.dsagent.classroom;

public record ClassroomEventRecord(
    String sessionId,
    ClassroomAction action,
    String content,
    ClassroomState fromState,
    ClassroomState toState,
    ClassroomAnswerEvaluation answerEvaluation
) {
}
