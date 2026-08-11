package com.feng.dsagent.chat;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.common.WindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class ChatRateLimiter {

    private final WindowRateLimiter guest = new WindowRateLimiter(8, Duration.ofMinutes(10));
    private final WindowRateLimiter authenticated = new WindowRateLimiter(30, Duration.ofMinutes(10));
    private final Clock clock;

    ChatRateLimiter(Clock clock) {
        this.clock = clock;
    }

    void check(Long userId, String remoteAddress) {
        boolean allowed = userId == null
            ? guest.allow("guest:" + safe(remoteAddress), clock.instant())
            : authenticated.allow("user:" + userId, clock.instant());
        if (!allowed) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_RATE_LIMITED", "提问过于频繁，请稍后重试");
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
