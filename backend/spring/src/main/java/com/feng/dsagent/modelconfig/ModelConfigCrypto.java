package com.feng.dsagent.modelconfig;

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
final class ModelConfigCrypto {

    private static final String VERSION = "v2";
    private static final int INITIALIZATION_VECTOR_BYTES = 12;
    private static final int AUTHENTICATION_TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    String encrypt(String value, SecretKey key, ModelConfigKeyBinding binding) {
        byte[] initializationVector = new byte[INITIALIZATION_VECTOR_BYTES];
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        random.nextBytes(initializationVector);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, initializationVector, binding);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return VERSION + ":" + encode(initializationVector) + ":" + encode(ciphertext);
        } catch (GeneralSecurityException error) {
            throw new CryptoFailure();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    String decrypt(String storedValue, SecretKey key, ModelConfigKeyBinding binding) {
        String[] parts = storedValue == null ? new String[0] : storedValue.split(":", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new CryptoFailure();
        }
        byte[] initializationVector;
        byte[] ciphertext;
        try {
            initializationVector = decode(parts[1]);
            ciphertext = decode(parts[2]);
        } catch (IllegalArgumentException error) {
            throw new CryptoFailure();
        }
        if (initializationVector.length != INITIALIZATION_VECTOR_BYTES || ciphertext.length == 0) {
            throw new CryptoFailure();
        }
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, initializationVector, binding);
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException error) {
            throw new CryptoFailure();
        }
    }

    private Cipher cipher(int mode, SecretKey key, byte[] initializationVector, ModelConfigKeyBinding binding)
        throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(AUTHENTICATION_TAG_BITS, initializationVector));
        cipher.updateAAD(binding.additionalAuthenticatedData());
        return cipher;
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    static final class CryptoFailure extends RuntimeException {
    }
}
