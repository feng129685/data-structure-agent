package com.feng.dsagent.compiler;

public record CodeAnalysisRequest(
    String language,
    String code,
    String stdin,
    String stdout,
    String stderr,
    String status,
    String chapterId,
    String runId
) {
}
