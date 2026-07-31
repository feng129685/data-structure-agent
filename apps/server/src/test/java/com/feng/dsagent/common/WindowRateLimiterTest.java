package com.feng.dsagent.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WindowRateLimiterTest {

    @Test
    void limitsEachKeyIndependentlyWithinWindow() {
        WindowRateLimiter limiter = new WindowRateLimiter(2, Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-07-20T03:30:00Z");

        assertThat(limiter.allow("ip-a", now)).isTrue();
        assertThat(limiter.allow("ip-a", now.plusSeconds(1))).isTrue();
        assertThat(limiter.allow("ip-a", now.plusSeconds(2))).isFalse();
        assertThat(limiter.allow("ip-b", now.plusSeconds(2))).isTrue();
    }

    @Test
    void resetsAfterWindowExpires() {
        WindowRateLimiter limiter = new WindowRateLimiter(1, Duration.ofSeconds(10));
        Instant now = Instant.parse("2026-07-20T03:30:00Z");

        assertThat(limiter.allow("student", now)).isTrue();
        assertThat(limiter.allow("student", now.plusSeconds(9))).isFalse();
        assertThat(limiter.allow("student", now.plusSeconds(10))).isTrue();
    }

    @Test
    void boundsTrackedKeysAndReclaimsExpiredEntries() {
        WindowRateLimiter limiter = new WindowRateLimiter(2, Duration.ofSeconds(10), 2);
        Instant now = Instant.parse("2026-07-20T03:30:00Z");

        assertThat(limiter.allow("ip-a", now)).isTrue();
        assertThat(limiter.allow("ip-b", now)).isTrue();
        assertThat(limiter.allow("ip-c", now)).isFalse();
        assertThat(limiter.trackedKeyCount()).isEqualTo(2);

        assertThat(limiter.allow("ip-c", now.plusSeconds(10))).isTrue();
        assertThat(limiter.trackedKeyCount()).isLessThanOrEqualTo(2);
    }
}
