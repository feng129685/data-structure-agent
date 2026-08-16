package com.feng.dsagent.auth;

import java.time.Duration;

public record AuthSession(String token, UserView user, Duration tokenTtl) {
    public AuthSession {
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("tokenTtl must be positive");
        }
    }
}
