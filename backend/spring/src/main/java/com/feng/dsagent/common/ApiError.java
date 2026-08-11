package com.feng.dsagent.common;

import java.util.List;

public record ApiError(String code, String message, String requestId, List<String> details) {

    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
