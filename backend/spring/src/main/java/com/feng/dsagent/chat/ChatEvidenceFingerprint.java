package com.feng.dsagent.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ChatEvidenceFingerprint {

    private ChatEvidenceFingerprint() {
    }

    static String hash(String title, String content, String source, String pageLabel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = safe(title) + "\u0000" + safe(content) + "\u0000" + safe(source) + "\u0000" + safe(pageLabel);
            byte[] value = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(value.length * 2);
            for (byte item : value) {
                encoded.append(String.format(java.util.Locale.ROOT, "%02x", Byte.toUnsignedInt(item)));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
