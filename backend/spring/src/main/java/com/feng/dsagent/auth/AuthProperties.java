package com.feng.dsagent.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(
    Duration verificationTtl,
    int maximumCodeAttempts,
    boolean exposeDevelopmentCode,
    boolean mailEnabled,
    String mailFrom
) {
}
