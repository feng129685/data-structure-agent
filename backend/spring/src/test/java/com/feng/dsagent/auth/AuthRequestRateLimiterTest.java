package com.feng.dsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AuthRequestRateLimiterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void limitsLoginByBothAccountAndClientAddress() {
        AuthRequestRateLimiter limiter = new AuthRequestRateLimiter(
            clock, 2, 3, 2, 3, Duration.ofMinutes(10)
        );

        limiter.checkLogin("student@example.com", "192.0.2.1");
        limiter.checkLogin("student@example.com", "192.0.2.1");
        assertRateLimited(() -> limiter.checkLogin("student@example.com", "192.0.2.2"), "AUTH_LOGIN_RATE_LIMITED");

        AuthRequestRateLimiter ipLimiter = new AuthRequestRateLimiter(
            clock, 3, 2, 2, 3, Duration.ofMinutes(10)
        );
        ipLimiter.checkLogin("one@example.com", "192.0.2.8");
        ipLimiter.checkLogin("two@example.com", "192.0.2.8");
        assertRateLimited(() -> ipLimiter.checkLogin("three@example.com", "192.0.2.8"), "AUTH_LOGIN_RATE_LIMITED");
    }

    @Test
    void limitsVerificationRequestsByBothEmailAndClientAddress() {
        AuthRequestRateLimiter limiter = new AuthRequestRateLimiter(
            clock, 3, 3, 2, 2, Duration.ofMinutes(10)
        );

        limiter.checkCodeRequest("student@example.com", "198.51.100.1");
        limiter.checkCodeRequest("student@example.com", "198.51.100.1");
        assertRateLimited(
            () -> limiter.checkCodeRequest("student@example.com", "198.51.100.2"),
            "AUTH_CODE_RATE_LIMITED"
        );
    }

    private static void assertRateLimited(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(error.code()).isEqualTo(code);
        });
    }
}
