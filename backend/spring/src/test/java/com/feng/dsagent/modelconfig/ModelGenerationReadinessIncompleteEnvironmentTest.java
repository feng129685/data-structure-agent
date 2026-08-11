package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ModelGenerationReadinessIncompleteEnvironmentTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ModelGenerationReadiness readiness;

    @BeforeEach
    void clearConfiguration() {
        jdbc.update("DELETE FROM model_configurations");
    }

    @Test
    void reportsIncompleteEnvironmentConfigurationWithoutExposingItsValues() {
        ModelGenerationReadiness.State state = readiness.current();

        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(ModelGenerationReadiness.Reason.ENVIRONMENT_CONFIGURATION_INCOMPLETE);
    }
}
