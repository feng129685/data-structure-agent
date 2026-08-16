package com.feng.dsagent.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class JwtTokenService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final String HEADER = URL_ENCODER.encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)
    );

    private final byte[] secret;
    private final Duration ttl;
    private final String issuer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JwtTokenService(String secret, Duration ttl, String issuer, ObjectMapper objectMapper, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.issuer = issuer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String issue(long userId, String email, Set<String> roles) {
        return issue(userId, email, roles, ttl);
    }

    public String issue(long userId, String email, Set<String> roles, Duration requestedTtl) {
        Duration effectiveTtl = requestedTtl == null ? ttl : requestedTtl;
        if (effectiveTtl.isNegative() || effectiveTtl.isZero()) {
            throw new IllegalArgumentException("token ttl must be positive");
        }
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", Long.toString(userId));
        payload.put("email", email);
        payload.put("roles", roles.stream().sorted().toList());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", now.plus(effectiveTtl).getEpochSecond());

        try {
            String encodedPayload = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            String unsigned = HEADER + "." + encodedPayload;
            return unsigned + "." + URL_ENCODER.encodeToString(sign(unsigned));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create authentication token", error);
        }
    }

    public Duration defaultTtl() {
        return ttl;
    }

    public AuthenticatedUser verify(String token) {
        try {
            JsonNode payload = verifiedPayload(token);
            if (!issuer.equals(payload.path("iss").asText())) {
                throw new InvalidTokenException("invalid token issuer");
            }
            if (clock.instant().getEpochSecond() >= payload.path("exp").asLong()) {
                throw new InvalidTokenException("token expired");
            }

            long userId = Long.parseLong(payload.path("sub").asText());
            String email = payload.path("email").asText();
            Set<String> roles = new LinkedHashSet<>();
            JsonNode roleNode = payload.path("roles");
            if (roleNode.isArray()) {
                roleNode.forEach(item -> roles.add(item.asText()));
            }
            return new AuthenticatedUser(userId, email, roles);
        } catch (InvalidTokenException error) {
            throw error;
        } catch (Exception error) {
            throw new InvalidTokenException("invalid token", error);
        }
    }

    /**
     * Verifies the pre-existing Node compatibility token shape. It deliberately
     * accepts no issuer, subject, or role claims so a malformed Spring token
     * cannot fall through into this migration-only path.
     */
    public NodeCompatibilityToken verifyNodeCompatibilityToken(String token) {
        try {
            JsonNode payload = verifiedPayload(token);
            if (payload.hasNonNull("iss") || payload.hasNonNull("sub") || payload.hasNonNull("roles")) {
                throw new InvalidTokenException("not a Node compatibility token");
            }
            if (clock.instant().getEpochSecond() >= payload.path("exp").asLong()) {
                throw new InvalidTokenException("token expired");
            }

            String rawUserId = payload.path("userId").asText();
            String email = payload.path("email").asText();
            long nodeUserId;
            try {
                nodeUserId = Long.parseLong(rawUserId);
            } catch (NumberFormatException invalidUserId) {
                throw new InvalidTokenException("invalid Node user id", invalidUserId);
            }
            if (nodeUserId <= 0 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length() > 254) {
                throw new InvalidTokenException("invalid Node compatibility claims");
            }
            return new NodeCompatibilityToken(nodeUserId, email);
        } catch (InvalidTokenException error) {
            throw error;
        } catch (Exception error) {
            throw new InvalidTokenException("invalid Node compatibility token", error);
        }
    }

    private JsonNode verifiedPayload(String token) throws Exception {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("invalid token format");
        }
        String unsigned = parts[0] + "." + parts[1];
        byte[] actualSignature = URL_DECODER.decode(parts[2]);
        if (!MessageDigest.isEqual(sign(unsigned), actualSignature)) {
            throw new InvalidTokenException("invalid token signature");
        }
        return objectMapper.readTree(URL_DECODER.decode(parts[1]));
    }

    private byte[] sign(String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}
