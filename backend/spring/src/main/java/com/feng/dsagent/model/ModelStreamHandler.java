package com.feng.dsagent.model;

@FunctionalInterface
public interface ModelStreamHandler {

    void onContent(String content);

    /**
     * Receives an optional provider-reported total token count from a terminal SSE chunk.
     * Implementations that only consume content remain source compatible.
     */
    default void onUsage(Long totalTokens) {
    }
}
