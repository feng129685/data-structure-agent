package com.feng.dsagent.aiquota;

import com.feng.dsagent.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps an untrusted request header out of the idempotency-key column while preserving retry identity.
 */
public final class AiQuotaRequestId {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private AiQuotaRequestId() {
    }

    public static String from(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return canonicalize(value == null ? null : value.toString());
    }

    public static String canonicalize(String value) {
        if (value != null) {
            String normalized = value.trim();
            if (SAFE_REQUEST_ID.matcher(normalized).matches()) {
                return normalized;
            }
            if (!normalized.isEmpty()) {
                return "req-" + digest(normalized);
            }
        }
        return UUID.randomUUID().toString();
    }

    public static String operationScoped(String operation, String requestId) {
        String normalizedOperation = operation == null ? "formal" : operation.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalizedOperation.matches("[a-z0-9-]{1,32}")) {
            normalizedOperation = "formal";
        }
        return "quota-" + digest(normalizedOperation + "\n" + canonicalize(requestId));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
