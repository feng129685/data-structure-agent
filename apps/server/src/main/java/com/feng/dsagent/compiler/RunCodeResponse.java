package com.feng.dsagent.compiler;

public record RunCodeResponse(
    String language,
    String status,
    String stdout,
    String stderr,
    long durationMs,
    String runId
) {
}
