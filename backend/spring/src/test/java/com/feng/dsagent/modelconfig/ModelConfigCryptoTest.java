package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ModelConfigCryptoTest {

    @Test
    void encryptsWithRandomAesGcmNonceAndRejectsAnIncorrectMasterKey() {
        ModelConfigCrypto crypto = new ModelConfigCrypto();
        SecretKey primaryKey = randomKey();
        String opaqueValue = UUID.randomUUID().toString();
        ModelConfigKeyBinding binding = ModelConfigKeyBinding.forConfiguration(
            1L,
            "openai-compatible",
            "https://models.example/v1"
        );

        String firstCiphertext = crypto.encrypt(opaqueValue, primaryKey, binding);
        String secondCiphertext = crypto.encrypt(opaqueValue, primaryKey, binding);

        assertThat(firstCiphertext).startsWith("v2:");
        assertThat(secondCiphertext).isNotEqualTo(firstCiphertext);
        assertThat(crypto.decrypt(firstCiphertext, primaryKey, binding)).isEqualTo(opaqueValue);
        assertThatThrownBy(() -> crypto.decrypt(firstCiphertext, randomKey(), binding))
            .isInstanceOf(ModelConfigCrypto.CryptoFailure.class);
    }

    private SecretKey randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }
}
