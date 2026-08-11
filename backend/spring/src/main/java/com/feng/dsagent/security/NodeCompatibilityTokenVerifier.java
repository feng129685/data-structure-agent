package com.feng.dsagent.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Verifies the bounded legacy Node token shape with its dedicated signing key. */
@Component
public final class NodeCompatibilityTokenVerifier {

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;
    private static final long MAX_NODE_TOKEN_LIFETIME_SECONDS = 8 * 24 * 60 * 60L;

    private final boolean enabled;
    private final byte[] secret;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeCompatibilityTokenVerifier(
        SecurityProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.enabled = properties.nodeCompatEnabled();
        this.objectMapper = objectMapper;
        this.clock = clock;
        if (!enabled) {
            this.secret = new byte[0];
            return;
        }

        String configuredSecret = properties.nodeCompatJwtSecret();
        if (configuredSecret == null || configuredSecret.length() < 32) {
            throw new IllegalArgumentException("NODE_COMPAT_JWT_SECRET must contain at least 32 characters when enabled");
        }
        this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean enabled() {
        return enabled;
    }

    public NodeCompatibilityToken verify(String token) {
        if (!enabled) {
            throw new InvalidTokenException("Node compatibility tokens are disabled");
        }
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.");
            if (parts.length != 3) {
                throw new InvalidTokenException("invalid Node compatibility token format");
            }

            JsonNode header = parse(parts[0], "header");
            if (!"HS256".equals(header.path("alg").asText()) || !"JWT".equals(header.path("typ").asText())) {
                throw new InvalidTokenException("invalid Node compatibility token header");
            }

            String unsigned = parts[0] + "." + parts[1];
            byte[] signature = URL_DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(sign(unsigned), signature)) {
                throw new InvalidTokenException("invalid Node compatibility token signature");
            }

            JsonNode payload = parse(parts[1], "payload");
            rejectSpringClaims(payload);
            long now = clock.instant().getEpochSecond();
            long issuedAt = epoch(payload, "iat");
            long expiresAt = epoch(payload, "exp");
            if (issuedAt > now + MAX_CLOCK_SKEW_SECONDS
                || expiresAt <= now
                || expiresAt <= issuedAt
                || expiresAt - issuedAt > MAX_NODE_TOKEN_LIFETIME_SECONDS) {
                throw new InvalidTokenException("invalid Node compatibility token lifetime");
            }

            JsonNode userIdNode = payload.path("userId");
            JsonNode emailNode = payload.path("email");
            if (!userIdNode.isIntegralNumber() || !emailNode.isTextual()) {
                throw new InvalidTokenException("invalid Node compatibility token claims");
            }
            long nodeUserId = userIdNode.longValue();
            String email = emailNode.asText();
            if (nodeUserId <= 0 || email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new InvalidTokenException("invalid Node compatibility token claims");
            }
            return new NodeCompatibilityToken(nodeUserId, email);
        } catch (InvalidTokenException error) {
            throw error;
        } catch (Exception error) {
            throw new InvalidTokenException("invalid Node compatibility token", error);
        }
    }

    private JsonNode parse(String encoded, String part) throws Exception {
        JsonNode parsed = objectMapper.readTree(URL_DECODER.decode(encoded));
        if (parsed == null || !parsed.isObject()) {
            throw new InvalidTokenException("invalid Node compatibility token " + part);
        }
        return parsed;
    }

    private void rejectSpringClaims(JsonNode payload) {
        if (payload.has("iss") || payload.has("sub") || payload.has("roles")) {
            throw new InvalidTokenException("not a Node compatibility token");
        }
    }

    private long epoch(JsonNode payload, String claim) {
        JsonNode node = payload.path(claim);
        if (!node.isIntegralNumber()) {
            throw new InvalidTokenException("invalid Node compatibility token " + claim);
        }
        return node.longValue();
    }

    private byte[] sign(String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}
