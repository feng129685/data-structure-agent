package com.feng.dsagent.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ModelFeatureRateLimiterTest {

    @Test
    void limitsGuestRequestsIndependentlyByFeature() {
        ModelFeatureRateLimiter limiter = new ModelFeatureRateLimiter(
            Clock.fixed(Instant.parse("2026-07-20T05:00:00Z"), ZoneOffset.UTC)
        );

        for (int request = 0; request < 8; request++) {
            limiter.check("animation", null, "192.0.2.50");
        }
        limiter.check("code-analysis", null, "192.0.2.50");

        assertThatThrownBy(() -> limiter.check("animation", null, "192.0.2.50"))
            .isInstanceOfSatisfying(ApiException.class, error ->
                org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("MODEL_FEATURE_RATE_LIMITED")
            );
    }
}
