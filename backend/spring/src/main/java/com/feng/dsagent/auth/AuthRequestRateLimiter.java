package com.feng.dsagent.auth;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.common.WindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class AuthRequestRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final Clock clock;
    private final WindowRateLimiter loginByAccount;
    private final WindowRateLimiter loginByClient;
    private final WindowRateLimiter codeRequestsByEmail;
    private final WindowRateLimiter codeRequestsByClient;
    private final WindowRateLimiter codeAttemptsByEmail;
    private final WindowRateLimiter codeAttemptsByClient;

    @Autowired
    AuthRequestRateLimiter(Clock clock) {
        this(clock, 5, 30, 3, 12, WINDOW);
    }

    AuthRequestRateLimiter(
        Clock clock,
        int loginAccountLimit,
        int loginClientLimit,
        int codeEmailLimit,
        int codeClientLimit,
        Duration window
    ) {
        this.clock = clock;
        this.loginByAccount = new WindowRateLimiter(loginAccountLimit, window);
        this.loginByClient = new WindowRateLimiter(loginClientLimit, window);
        this.codeRequestsByEmail = new WindowRateLimiter(codeEmailLimit, window);
        this.codeRequestsByClient = new WindowRateLimiter(codeClientLimit, window);
        this.codeAttemptsByEmail = new WindowRateLimiter(10, window);
        this.codeAttemptsByClient = new WindowRateLimiter(30, window);
    }

    void checkLogin(String email, String clientAddress) {
        if (!loginByAccount.allow(emailKey(email), clock.instant())
                || !loginByClient.allow(clientKey(clientAddress), clock.instant())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_LOGIN_RATE_LIMITED", "登录请求过于频繁，请稍后重试");
        }
    }

    void checkCodeRequest(String email, String clientAddress) {
        if (!codeRequestsByEmail.allow(emailKey(email), clock.instant())
                || !codeRequestsByClient.allow(clientKey(clientAddress), clock.instant())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_CODE_RATE_LIMITED", "验证码请求过于频繁");
        }
    }

    void checkCodeAttempt(String email, String clientAddress) {
        if (!codeAttemptsByEmail.allow(emailKey(email), clock.instant())
                || !codeAttemptsByClient.allow(clientKey(clientAddress), clock.instant())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_CODE_RATE_LIMITED", "验证码验证过于频繁");
        }
    }

    private String emailKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return "email:" + normalized;
    }

    private String clientKey(String address) {
        String normalized = address == null || address.isBlank() ? "unknown" : address.trim();
        return "ip:" + normalized;
    }
}
