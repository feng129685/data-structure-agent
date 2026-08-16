package com.feng.dsagent.auth;

import com.feng.dsagent.common.ApiException;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Centralizes username validation and the stable lookup key used by persistence. */
public final class UsernamePolicy {

    public static final String REGEX = "^[A-Za-z0-9_]{3,32}$";

    private UsernamePolicy() {
    }

    public static String normalizeOptional(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return normalizeRequired(username);
    }

    public static String normalizeRequired(String username) {
        String normalized = username == null ? "" : username.trim();
        if (!normalized.matches(REGEX)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "AUTH_USERNAME_INVALID",
                "用户名只能包含 3 到 32 位英文字母、数字或下划线"
            );
        }
        return normalized;
    }

    public static String lookupKey(String username) {
        return normalizeRequired(username).toLowerCase(Locale.ROOT);
    }
}
