package com.feng.dsagent.compiler;

public record RunCodeRequest(String language, String code, String stdin, String chapterId) {

    public RunCodeRequest(String language, String code, String stdin) {
        this(language, code, stdin, null);
    }
}
