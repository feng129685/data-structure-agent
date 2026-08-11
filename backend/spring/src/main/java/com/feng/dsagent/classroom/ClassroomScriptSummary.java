package com.feng.dsagent.classroom;

public record ClassroomScriptSummary(String id, String chapterId, String title, String versionLabel) {

    static ClassroomScriptSummary from(ClassroomScript script) {
        return new ClassroomScriptSummary(script.id(), script.chapterId(), script.title(), script.versionLabel());
    }
}
