package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ModelConfigEnablementTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ModelGenerationReadiness readiness;

    @Autowired
    private DatabaseModelConfigRuntimeSettingsSource runtimeSettings;

    @BeforeEach
    void clearConfiguration() {
        jdbc.update("DELETE FROM model_configurations");
    }

    @Test
    void disabledPersistedConfigurationIsNeverEligibleForFormalGeneration() {
        insertDisabledConfiguration();

        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.PERSISTED_CONFIGURATION_DISABLED);
    }

    @Test
    void disabledPersistedConfigurationCannotBeSelectedByTheRuntimeClient() {
        insertDisabledConfiguration();

        assertThatThrownBy(runtimeSettings::current)
            .isInstanceOf(ModelConfigRuntimeUnavailableException.class);
    }

    private void insertDisabledConfiguration() {
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
            "ciphertext",
            0.2,
            1_024,
            45_000,
            0,
            10_000,
            false
        );
    }
}
