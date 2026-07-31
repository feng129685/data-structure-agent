package com.feng.dsagent.classroom;

public record ClassroomScript(
    String id,
    String chapterId,
    String title,
    String versionLabel,
    String scriptJson
) {
}
