package com.feng.dsagent.modelconfig;

import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class EnvironmentModelConfigMasterKeySource implements ModelConfigMasterKeySource {

    static final String ENVIRONMENT_VARIABLE = "MODEL_CONFIG_MASTER_KEY";

    @Override
    public Optional<SecretKey> masterKey() {
        String encoded = System.getenv(ENVIRONMENT_VARIABLE);
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded.strip());
            if (bytes.length != 32) {
                return Optional.empty();
            }
            return Optional.of(new SecretKeySpec(bytes, "AES"));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
