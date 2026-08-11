CREATE TABLE background_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    deadline_at TIMESTAMP NULL,
    heartbeat_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    failure_code VARCHAR(96) NULL,
    failure_reason VARCHAR(1000) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    cancel_requested_at TIMESTAMP NULL,
    requested_by_user_id BIGINT NULL,
    request_id VARCHAR(128) NOT NULL DEFAULT '',
    CONSTRAINT fk_background_tasks_requester
        FOREIGN KEY (requested_by_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_background_tasks_status_created
    ON background_tasks(status, created_at DESC);
CREATE INDEX idx_background_tasks_deadline
    ON background_tasks(deadline_at, status);
CREATE INDEX idx_background_tasks_type_created
    ON background_tasks(task_type, created_at DESC);
