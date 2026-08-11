ALTER TABLE users ADD COLUMN disabled_reason VARCHAR(500) NULL;
ALTER TABLE users ADD COLUMN disabled_at TIMESTAMP NULL;

CREATE TABLE admin_audit_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    result VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL DEFAULT '',
    before_summary VARCHAR(2000) NOT NULL DEFAULT '',
    after_summary VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users(id)
);

CREATE INDEX idx_admin_audit_created ON admin_audit_events(created_at DESC);
CREATE INDEX idx_admin_audit_actor_created ON admin_audit_events(actor_user_id, created_at DESC);
CREATE INDEX idx_admin_audit_target_created ON admin_audit_events(target_type, target_id, created_at DESC);
