package com.feng.dsagent.model;

public interface ModelClient {

    ModelResponse complete(ModelRequest request);

    void stream(ModelRequest request, ModelStreamHandler handler);
}
