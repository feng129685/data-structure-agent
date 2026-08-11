package com.feng.dsagent.modelconfig;

import java.time.Duration;

final class ModelConfigRuntimeSettings {

    private final String provider;
    private final ModelConfigResolvedTarget target;
    private final String model;
    private final String apiKey;
    private final double temperature;
    private final int maxOutputTokens;
    private final Duration requestTimeout;
    private final int retryCount;
    private final long dailyTokenQuota;

    ModelConfigRuntimeSettings(String provider, ModelConfigResolvedTarget target, String model, String apiKey) {
        this(
            provider,
            target,
            model,
            apiKey,
            ModelConfigGenerationControls.DEFAULT_TEMPERATURE,
            ModelConfigGenerationControls.DEFAULT_MAX_OUTPUT_TOKENS,
            Duration.ofMillis(ModelConfigGenerationControls.DEFAULT_REQUEST_TIMEOUT_MS),
            ModelConfigGenerationControls.DEFAULT_RETRY_COUNT,
            ModelConfigGenerationControls.DEFAULT_DAILY_TOKEN_QUOTA
        );
    }

    ModelConfigRuntimeSettings(
        String provider,
        ModelConfigResolvedTarget target,
        String model,
        String apiKey,
        double temperature,
        int maxOutputTokens,
        Duration requestTimeout,
        int retryCount,
        long dailyTokenQuota
    ) {
        this.provider = provider;
        this.target = target;
        this.model = model;
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.requestTimeout = requestTimeout;
        this.retryCount = retryCount;
        this.dailyTokenQuota = dailyTokenQuota;
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

    double temperature() {
        return temperature;
    }

    int maxOutputTokens() {
        return maxOutputTokens;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    int retryCount() {
        return retryCount;
    }

    long dailyTokenQuota() {
        return dailyTokenQuota;
    }
}
