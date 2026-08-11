CREATE TABLE ai_quota_buckets (
    user_id BIGINT NOT NULL,
    quota_date DATE NOT NULL,
    daily_token_quota BIGINT NOT NULL,
    reserved_tokens BIGINT NOT NULL DEFAULT 0,
    consumed_tokens BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, quota_date),
    CONSTRAINT fk_ai_quota_buckets_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE ai_quota_user_concurrency (
    user_id BIGINT PRIMARY KEY,
    active_reservations INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_quota_user_concurrency_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE ai_quota_reservations (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    quota_date DATE NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    estimated_tokens BIGINT NOT NULL,
    actual_tokens BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    failure_code VARCHAR(96) NULL,
    CONSTRAINT uq_ai_quota_reservation_request UNIQUE (user_id, quota_date, request_id),
    CONSTRAINT fk_ai_quota_reservation_bucket
        FOREIGN KEY (user_id, quota_date) REFERENCES ai_quota_buckets(user_id, quota_date) ON DELETE CASCADE
);

CREATE INDEX idx_ai_quota_reservations_expiry
    ON ai_quota_reservations(status, expires_at);
CREATE INDEX idx_ai_quota_reservations_user_status
    ON ai_quota_reservations(user_id, status, expires_at);
