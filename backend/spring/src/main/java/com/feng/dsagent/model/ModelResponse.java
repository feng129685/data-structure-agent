package com.feng.dsagent.model;

/**
 * Provider-reported token usage is optional because some OpenAI-compatible providers omit it.
 */
public record ModelResponse(String content, Long totalTokens) {

    public ModelResponse(String content) {
        this(content, null);
    }
}
