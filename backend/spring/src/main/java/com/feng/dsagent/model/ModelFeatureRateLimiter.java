package com.feng.dsagent.model;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.common.WindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class ModelFeatureRateLimiter {

    private final WindowRateLimiter guest = new WindowRateLimiter(8, Duration.ofMinutes(10));
    private final WindowRateLimiter authenticated = new WindowRateLimiter(30, Duration.ofMinutes(10));
    private final Clock clock;

    public ModelFeatureRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String feature, Long userId, String remoteAddress) {
        String normalizedFeature = feature == null || feature.isBlank() ? "unknown" : feature.trim();
        boolean allowed = userId == null
            ? guest.allow(normalizedFeature + ":guest:" + safe(remoteAddress), clock.instant())
            : authenticated.allow(normalizedFeature + ":user:" + userId, clock.instant());
        if (!allowed) {
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "MODEL_FEATURE_RATE_LIMITED",
                "请求过于频繁，请稍后重试"
            );
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
