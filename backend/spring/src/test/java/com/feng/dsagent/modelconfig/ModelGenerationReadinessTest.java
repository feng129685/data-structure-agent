package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "app.model.provider=environment",
    "app.model.api-key=environment-key",
    "app.model.base-url=https://environment.example/v1",
    "app.model.name=environment-model",
    "app.ai-quota.daily-token-quota=10000"
})
@Import(ModelGenerationReadinessTest.TestModelConfigConfiguration.class)
class ModelGenerationReadinessTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ModelGenerationReadiness readiness;

    @Autowired
    private ModelConfigMasterKeySource masterKeySource;

    @BeforeEach
    void clearConfiguration() {
        jdbc.update("DELETE FROM model_configurations");
    }

    @Test
    void reportsADecryptableSafelyResolvedPersistedConfiguration() {
        insertPersistedConfiguration(encryptedKey("opaque-key"));

        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isTrue();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.PERSISTED_CONFIGURATION_READY);
    }

    @Test
    void failsClosedWhenAPersistedConfigurationCannotBeDecryptedEvenIfEnvironmentValuesAreComplete() {
        insertPersistedConfiguration("invalid-ciphertext");

        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.PERSISTED_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenAPersistedCredentialCannotBeSafelyUsedAsAnHttpHeader() {
        insertPersistedConfiguration(encryptedKey("opaque\r\nkey"));

        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.PERSISTED_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenAPersistedProviderIsBlank() {
        insertPersistedConfiguration(" ", "https://model.example/v1", "model-a", "invalid-ciphertext");

        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.PERSISTED_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenAPersistedModelIsBlank() {
        insertPersistedConfiguration("custom", "https://model.example/v1", " ", "invalid-ciphertext");

        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.PERSISTED_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenAPersistedUrlIsStructurallyUnsafeEvenWithADecryptableCredential() {
        String unsafeUrl = "https://127.0.0.1/v1";
        insertPersistedConfiguration(
            "custom",
            unsafeUrl,
            "model-a",
            encryptedKey("opaque-key", "custom", unsafeUrl)
        );

        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.PERSISTED_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void usesEnvironmentReadinessOnlyWhenNoPersistedConfigurationExists() {
        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isTrue();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.ENVIRONMENT_CONFIGURATION_READY);
    }

    private String encryptedKey(String value) {
        return encryptedKey(value, "custom", "https://model.example/v1");
    }

    private String encryptedKey(String value, String provider, String baseUrl) {
        SecretKey key = masterKeySource.masterKey().orElseThrow();
        return new ModelConfigCrypto().encrypt(
            value,
            key,
            ModelConfigKeyBinding.forConfiguration(1L, provider, baseUrl)
        );
    }

    private void insertPersistedConfiguration(String ciphertext) {
        insertPersistedConfiguration("custom", "https://model.example/v1", "model-a", ciphertext);
    }

    private void insertPersistedConfiguration(String provider, String baseUrl, String model, String ciphertext) {
        jdbc.update(
            """
                INSERT INTO model_configurations (
                    id, provider, base_url, model_name, api_key_ciphertext,
                    temperature, max_output_tokens, request_timeout_ms, retry_count, daily_token_quota, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            1L,
            provider,
            baseUrl,
            model,
            ciphertext,
            0.2,
            1_024,
            45_000,
            0,
            10_000,
            true
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestModelConfigConfiguration {

        @Bean
        @Primary
        ModelConfigMasterKeySource testMasterKeySource() {
            SecretKey key = new SecretKeySpec(new byte[32], "AES");
            return () -> Optional.of(key);
        }

        @Bean
        @Primary
        ModelConfigHostResolver testModelConfigHostResolver() {
            return host -> "127.0.0.1".equals(host)
                ? new InetAddress[] {InetAddress.getByAddress(new byte[] {127, 0, 0, 1})}
                : new InetAddress[] {InetAddress.getByAddress(new byte[] {1, 1, 1, 1})};
        }
    }
}
