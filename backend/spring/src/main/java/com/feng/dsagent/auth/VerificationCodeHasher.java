package com.feng.dsagent.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class VerificationCodeHasher {

    private final byte[] secret;

    VerificationCodeHasher(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    String hash(String email, String purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                (email + ":" + purpose + ":" + code).getBytes(StandardCharsets.UTF_8)
            ));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to hash verification code", error);
        }
    }

    boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
