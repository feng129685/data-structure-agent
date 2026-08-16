package com.feng.dsagent.mail;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MailConfigRepository {

    static final long CONFIGURATION_ID = 1L;

    private final JdbcTemplate jdbc;

    MailConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredMailConfig> find() {
        return jdbc.query(
            """
                SELECT id, site_name, enabled, smtp_host, smtp_port, security_mode, smtp_username,
                       smtp_password_ciphertext, from_email, from_name, connection_timeout_seconds,
                       verification_ttl_minutes, resend_interval_seconds, session_ttl_days,
                       verification_subject, verification_template_html, last_connection_test_status,
                       last_connection_tested_at, updated_at
                FROM mail_configurations WHERE id = ?
                """,
            (row, index) -> new StoredMailConfig(
                row.getLong("id"),
                row.getString("site_name"),
                row.getBoolean("enabled"),
                row.getString("smtp_host"),
                row.getInt("smtp_port"),
                row.getString("security_mode"),
                row.getString("smtp_username"),
                row.getString("smtp_password_ciphertext"),
                row.getString("from_email"),
                row.getString("from_name"),
                row.getInt("connection_timeout_seconds"),
                row.getInt("verification_ttl_minutes"),
                row.getInt("resend_interval_seconds"),
                row.getInt("session_ttl_days"),
                row.getString("verification_subject"),
                row.getString("verification_template_html"),
                row.getString("last_connection_test_status"),
                instant(row.getTimestamp("last_connection_tested_at")),
                instant(row.getTimestamp("updated_at"))
            ),
            CONFIGURATION_ID
        ).stream().findFirst();
    }

    StoredMailConfig save(StoredMailConfig config) {
        int updated = jdbc.update(
            """
                UPDATE mail_configurations
                SET site_name = ?, enabled = ?, smtp_host = ?, smtp_port = ?, security_mode = ?,
                    smtp_username = ?, smtp_password_ciphertext = ?, from_email = ?, from_name = ?,
                    connection_timeout_seconds = ?, verification_ttl_minutes = ?, resend_interval_seconds = ?,
                    session_ttl_days = ?, verification_subject = ?, verification_template_html = ?,
                    last_connection_test_status = NULL, last_connection_tested_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
            config.siteName(), config.enabled(), config.smtpHost(), config.smtpPort(), config.securityMode(),
            config.smtpUsername(), config.smtpPasswordCiphertext(), config.fromEmail(), config.fromName(),
            config.connectionTimeoutSeconds(), config.verificationTtlMinutes(), config.resendIntervalSeconds(),
            config.sessionTtlDays(), config.verificationSubject(), config.verificationTemplateHtml(), config.id()
        );
        if (updated == 0) {
            try {
                jdbc.update(
                    """
                        INSERT INTO mail_configurations (
                            id, site_name, enabled, smtp_host, smtp_port, security_mode, smtp_username,
                            smtp_password_ciphertext, from_email, from_name, connection_timeout_seconds,
                            verification_ttl_minutes, resend_interval_seconds, session_ttl_days,
                            verification_subject, verification_template_html
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    config.id(), config.siteName(), config.enabled(), config.smtpHost(), config.smtpPort(),
                    config.securityMode(), config.smtpUsername(), config.smtpPasswordCiphertext(),
                    config.fromEmail(), config.fromName(), config.connectionTimeoutSeconds(),
                    config.verificationTtlMinutes(), config.resendIntervalSeconds(), config.sessionTtlDays(),
                    config.verificationSubject(), config.verificationTemplateHtml()
                );
            } catch (DuplicateKeyException ignored) {
                save(config);
            }
        }
        return find().orElseThrow(() -> new IllegalStateException("Mail configuration was not persisted"));
    }

    StoredMailConfig recordConnectionTest(String status) {
        jdbc.update(
            """
                UPDATE mail_configurations
                SET last_connection_test_status = ?, last_connection_tested_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
            status, CONFIGURATION_ID
        );
        return find().orElseThrow(() -> new IllegalStateException("Mail configuration was not persisted"));
    }

    void appendAuditEvent(
        long actorUserId,
        String action,
        String targetId,
        String result,
        String requestId,
        String beforeSummary,
        String afterSummary
    ) {
        jdbc.update(
            """
                INSERT INTO admin_audit_events (
                    actor_user_id, action, target_type, target_id, result, request_id, before_summary, after_summary
                ) VALUES (?, ?, 'MAIL_CONFIG', ?, ?, ?, ?, ?)
                """,
            actorUserId, action, targetId, result, requestId, beforeSummary, afterSummary
        );
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    record StoredMailConfig(
        long id,
        String siteName,
        boolean enabled,
        String smtpHost,
        int smtpPort,
        String securityMode,
        String smtpUsername,
        String smtpPasswordCiphertext,
        String fromEmail,
        String fromName,
        int connectionTimeoutSeconds,
        int verificationTtlMinutes,
        int resendIntervalSeconds,
        int sessionTtlDays,
        String verificationSubject,
        String verificationTemplateHtml,
        String lastConnectionTestStatus,
        Instant lastConnectionTestedAt,
        Instant updatedAt
    ) {
    }
}
