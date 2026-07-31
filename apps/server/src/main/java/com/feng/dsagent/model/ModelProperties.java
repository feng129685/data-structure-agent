package com.feng.dsagent.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.model")
public record ModelProperties(
    String provider,
    String apiKey,
    String baseUrl,
    String name,
    Duration timeout,
    Duration streamIdleTimeout,
    int maximumResponseBytes
) {
}
