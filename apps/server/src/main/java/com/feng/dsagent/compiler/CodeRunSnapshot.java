package com.feng.dsagent.compiler;

record CodeRunSnapshot(
    String id,
    String chapterId,
    String language,
    String code,
    String stdin,
    String stdout,
    String stderr,
    String status
) {
}
