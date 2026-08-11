package com.feng.dsagent.compiler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CompilerConcurrencyLimiterTest {

    @Test
    void enforcesTheGlobalLimitAcrossDifferentClients() {
        CompilerConcurrencyLimiter limiter = new CompilerConcurrencyLimiter(1, 1);
        try (CompilerConcurrencyLimiter.Permit ignored = limiter.acquire("user:7")) {
            assertLimited(() -> limiter.acquire("user:8"));
        }
    }

    @Test
    void enforcesThePerClientLimitWithoutBlockingOtherClients() {
        CompilerConcurrencyLimiter limiter = new CompilerConcurrencyLimiter(2, 1);
        try (CompilerConcurrencyLimiter.Permit ignored = limiter.acquire("ip:192.0.2.1")) {
            assertLimited(() -> limiter.acquire("ip:192.0.2.1"));
            try (CompilerConcurrencyLimiter.Permit other = limiter.acquire("ip:192.0.2.2")) {
                // The second client still has one slot while the first client is capped at one.
            }
        }
    }

    private static void assertLimited(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            org.assertj.core.api.Assertions.assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("COMPILER_CONCURRENCY_LIMITED");
        });
    }
}
