package com.feng.dsagent.mail;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

@Component
final class MailConfigCrypto {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    String encrypt(String value, SecretKey key, MailConfigKeyBinding binding) {
        if (value == null) {
            throw new CryptoFailure();
        }
        byte[] iv = new byte[IV_BYTES];
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        random.nextBytes(iv);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, iv, binding);
            return VERSION + ":" + encode(iv) + ":" + encode(cipher.doFinal(plaintext));
        } catch (GeneralSecurityException error) {
            throw new CryptoFailure();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    String decrypt(String storedValue, SecretKey key, MailConfigKeyBinding binding) {
        String[] parts = storedValue == null ? new String[0] : storedValue.split(":", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new CryptoFailure();
        }
        try {
            byte[] iv = decode(parts[1]);
            byte[] ciphertext = decode(parts[2]);
            if (iv.length != IV_BYTES || ciphertext.length == 0) {
                throw new CryptoFailure();
            }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, iv, binding);
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new CryptoFailure();
        }
    }

    private Cipher cipher(int mode, SecretKey key, byte[] iv, MailConfigKeyBinding binding)
        throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(binding.additionalAuthenticatedData().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    static final class CryptoFailure extends RuntimeException {
    }
}
