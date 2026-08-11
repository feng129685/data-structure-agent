package com.feng.dsagent.model;

@FunctionalInterface
public interface ModelStreamHandler {

    void onContent(String content);
}
