package com.feng.dsagent.classroom;

public record ClassroomSessionRecord(
    String id,
    long userId,
    String scriptId,
    String chapterId,
    ClassroomState state,
    boolean paused,
    String summary,
    String scriptJson
) {
}
