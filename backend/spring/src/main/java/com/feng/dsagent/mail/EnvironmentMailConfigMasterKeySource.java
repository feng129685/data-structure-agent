package com.feng.dsagent.mail;

import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class EnvironmentMailConfigMasterKeySource implements MailConfigMasterKeySource {

    static final String ENVIRONMENT_VARIABLE = "MAIL_CONFIG_MASTER_KEY";
    private static final String FALLBACK_ENVIRONMENT_VARIABLE = "MODEL_CONFIG_MASTER_KEY";

    @Override
    public Optional<SecretKey> masterKey() {
        String encoded = System.getenv(ENVIRONMENT_VARIABLE);
        if (encoded == null || encoded.isBlank()) {
            encoded = System.getenv(FALLBACK_ENVIRONMENT_VARIABLE);
        }
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
