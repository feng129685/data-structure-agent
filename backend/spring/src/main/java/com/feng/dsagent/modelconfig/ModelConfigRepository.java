package com.feng.dsagent.modelconfig;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ModelConfigRepository {

    static final long CONFIGURATION_ID = 1L;

    private final JdbcTemplate jdbc;

    ModelConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredModelConfig> find() {
        return jdbc.query(
            """
                SELECT id, provider, base_url, model_name, api_key_ciphertext,
                       temperature, max_output_tokens, request_timeout_ms, retry_count, daily_token_quota, enabled,
                       last_test_status, last_tested_at, updated_at
                FROM model_configurations WHERE id = ?
                """,
            (row, index) -> new StoredModelConfig(
                row.getLong("id"),
                row.getString("provider"),
                row.getString("base_url"),
                row.getString("model_name"),
                row.getString("api_key_ciphertext"),
                row.getDouble("temperature"),
                row.getInt("max_output_tokens"),
                row.getLong("request_timeout_ms"),
                row.getInt("retry_count"),
                row.getLong("daily_token_quota"),
                row.getBoolean("enabled"),
                row.getString("last_test_status"),
                instant(row.getTimestamp("last_tested_at")),
                instant(row.getTimestamp("updated_at"))
            ),
            CONFIGURATION_ID
        ).stream().findFirst();
    }

    StoredModelConfig save(
        String provider,
        String baseUrl,
        String model,
        String apiKeyCiphertext,
        ModelConfigGenerationControls controls,
        boolean clearConnectionTest
    ) {
        int updated = update(provider, baseUrl, model, apiKeyCiphertext, controls, clearConnectionTest);
        if (updated == 0) {
            try {
                jdbc.update(
                    """
                        INSERT INTO model_configurations (
                            id, provider, base_url, model_name, api_key_ciphertext,
                            temperature, max_output_tokens, request_timeout_ms, retry_count, daily_token_quota, enabled
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    CONFIGURATION_ID,
                    provider,
                    baseUrl,
                    model,
                    apiKeyCiphertext,
                    controls.temperature(),
                    controls.maxOutputTokens(),
                    controls.requestTimeoutMs(),
                    controls.retryCount(),
                    controls.dailyTokenQuota(),
                    controls.enabled()
                );
            } catch (DuplicateKeyException ignored) {
                update(provider, baseUrl, model, apiKeyCiphertext, controls, clearConnectionTest);
            }
        }
        return find().orElseThrow(() -> new IllegalStateException("Model configuration was not persisted"));
    }

    StoredModelConfig recordConnectionTest(String status) {
        jdbc.update(
            """
                UPDATE model_configurations
                SET last_test_status = ?, last_tested_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
            status,
            CONFIGURATION_ID
        );
        return find().orElseThrow(() -> new IllegalStateException("Model configuration was not persisted"));
    }

    void appendAuditEvent(
        long actorUserId,
        String action,
        String result,
        String requestId,
        String beforeSummary,
        String afterSummary
    ) {
        jdbc.update(
            """
                INSERT INTO admin_audit_events (
                    actor_user_id, action, target_type, target_id, result, request_id, before_summary, after_summary
                ) VALUES (?, ?, 'MODEL_CONFIG', '1', ?, ?, ?, ?)
                """,
            actorUserId,
            action,
            result,
            requestId,
            beforeSummary,
            afterSummary
        );
    }

    private int update(
        String provider,
        String baseUrl,
        String model,
        String apiKeyCiphertext,
        ModelConfigGenerationControls controls,
        boolean clearConnectionTest
    ) {
        String resetConnectionTest = clearConnectionTest
            ? ", last_test_status = NULL, last_tested_at = NULL"
            : "";
        return jdbc.update(
            """
                UPDATE model_configurations
                SET provider = ?, base_url = ?, model_name = ?, api_key_ciphertext = ?,
                    temperature = ?, max_output_tokens = ?, request_timeout_ms = ?, retry_count = ?,
                    daily_token_quota = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP%s
                WHERE id = ?
                """.formatted(resetConnectionTest),
            provider,
            baseUrl,
            model,
            apiKeyCiphertext,
            controls.temperature(),
            controls.maxOutputTokens(),
            controls.requestTimeoutMs(),
            controls.retryCount(),
            controls.dailyTokenQuota(),
            controls.enabled(),
            CONFIGURATION_ID
        );
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    record StoredModelConfig(
        long id,
        String provider,
        String baseUrl,
        String model,
        String apiKeyCiphertext,
        double temperature,
        int maxOutputTokens,
        long requestTimeoutMs,
        int retryCount,
        long dailyTokenQuota,
        boolean enabled,
        String lastConnectionTestStatus,
        Instant lastConnectionTestedAt,
        Instant updatedAt
    ) {

        StoredModelConfig(
            long id,
            String provider,
            String baseUrl,
            String model,
            String apiKeyCiphertext,
            Instant updatedAt
        ) {
            this(
                id,
                provider,
                baseUrl,
                model,
                apiKeyCiphertext,
                ModelConfigGenerationControls.DEFAULT_TEMPERATURE,
                ModelConfigGenerationControls.DEFAULT_MAX_OUTPUT_TOKENS,
                ModelConfigGenerationControls.DEFAULT_REQUEST_TIMEOUT_MS,
                ModelConfigGenerationControls.DEFAULT_RETRY_COUNT,
                ModelConfigGenerationControls.DEFAULT_DAILY_TOKEN_QUOTA,
                false,
                null,
                null,
                updatedAt
            );
        }
    }
}
