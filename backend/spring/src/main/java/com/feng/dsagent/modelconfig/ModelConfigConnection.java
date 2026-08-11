package com.feng.dsagent.modelconfig;

final class ModelConfigConnection {

    private final String provider;
    private final ModelConfigResolvedTarget target;
    private final String model;
    private final String apiKey;

    ModelConfigConnection(String provider, ModelConfigResolvedTarget target, String model, String apiKey) {
        this.provider = provider;
        this.target = target;
        this.model = model;
        this.apiKey = apiKey;
    }

    String provider() {
        return provider;
    }

    ModelConfigResolvedTarget target() {
        return target;
    }

    String model() {
        return model;
    }

    String apiKey() {
        return apiKey;
    }
}
