CREATE TABLE mail_configurations (
    id BIGINT PRIMARY KEY,
    site_name VARCHAR(128) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    smtp_host VARCHAR(253) NOT NULL DEFAULT '',
    smtp_port INT NOT NULL DEFAULT 465,
    security_mode VARCHAR(16) NOT NULL DEFAULT 'SSL',
    smtp_username VARCHAR(320) NOT NULL DEFAULT '',
    smtp_password_ciphertext LONGTEXT NULL,
    from_email VARCHAR(254) NOT NULL DEFAULT '',
    from_name VARCHAR(128) NOT NULL DEFAULT '',
    connection_timeout_seconds INT NOT NULL DEFAULT 12,
    verification_ttl_minutes INT NOT NULL DEFAULT 10,
    resend_interval_seconds INT NOT NULL DEFAULT 60,
    session_ttl_days INT NOT NULL DEFAULT 30,
    verification_subject VARCHAR(300) NOT NULL DEFAULT '',
    verification_template_html LONGTEXT NOT NULL,
    last_connection_test_status VARCHAR(64) NULL,
    last_connection_tested_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_mail_config_updated ON mail_configurations(updated_at);
