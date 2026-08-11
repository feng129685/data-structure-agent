package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Duration;
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

@SpringBootTest
@Import(DatabaseModelConfigRuntimeSettingsSourceTest.TestModelConfigConfiguration.class)
class DatabaseModelConfigRuntimeSettingsSourceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseModelConfigRuntimeSettingsSource source;

    @Autowired
    private ModelConfigMasterKeySource masterKeySource;

    @BeforeEach
    void clearConfiguration() {
        jdbc.update("DELETE FROM model_configurations");
    }

    @Test
    void mapsEnabledPersistedRuntimeControlsIntoThePinnedModelClientSettings() {
        SecretKey key = masterKeySource.masterKey().orElseThrow();
        String ciphertext = new ModelConfigCrypto().encrypt(
            "opaque-key",
            key,
            ModelConfigKeyBinding.forConfiguration(1L, "custom", "https://model.example/v1")
        );
        jdbc.update(
            """
                INSERT INTO model_configurations (
                    id, provider, base_url, model_name, api_key_ciphertext,
                    temperature, max_output_tokens, request_timeout_ms, retry_count, daily_token_quota, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            1L,
            "custom",
            "https://model.example/v1",
            "model-a",
            ciphertext,
            0.35,
            640,
            12_000,
            2,
            4_096,
            true
        );

        ModelConfigRuntimeSettings settings = source.current().orElseThrow();

        assertThat(settings.temperature()).isEqualTo(0.35);
        assertThat(settings.maxOutputTokens()).isEqualTo(640);
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(settings.retryCount()).isEqualTo(2);
        assertThat(settings.dailyTokenQuota()).isEqualTo(4_096);
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
            return ignored -> new InetAddress[] {InetAddress.getByAddress(new byte[] {1, 1, 1, 1})};
        }
    }
}
