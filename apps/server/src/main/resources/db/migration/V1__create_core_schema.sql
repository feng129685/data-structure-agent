CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE verification_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_verification_email_purpose ON verification_codes(email, purpose, created_at);

CREATE TABLE refresh_tokens (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE chapters (
    id VARCHAR(64) PRIMARY KEY,
    chapter_number INT NOT NULL UNIQUE,
    title VARCHAR(120) NOT NULL,
    summary VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE resources (
    id VARCHAR(64) PRIMARY KEY,
    chapter_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    file_path VARCHAR(1000),
    source_name VARCHAR(300) NOT NULL DEFAULT '',
    version_label VARCHAR(64) NOT NULL DEFAULT '1.0',
    review_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    license_scope VARCHAR(32) NOT NULL DEFAULT 'TEAM_ONLY',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_resources_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id)
);
CREATE INDEX idx_resources_chapter_status ON resources(chapter_id, review_status, updated_at);

CREATE TABLE knowledge_chunks (
    id VARCHAR(96) PRIMARY KEY,
    chapter_id VARCHAR(64),
    resource_id VARCHAR(64),
    title VARCHAR(300) NOT NULL,
    content LONGTEXT NOT NULL,
    source_path VARCHAR(1000) NOT NULL,
    page_label VARCHAR(120),
    review_status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id),
    CONSTRAINT fk_knowledge_resource FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE SET NULL
);
CREATE INDEX idx_knowledge_chapter_status ON knowledge_chunks(chapter_id, review_status);

CREATE TABLE content_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resource_id VARCHAR(64) NOT NULL,
    reviewer_user_id BIGINT,
    review_status VARCHAR(32) NOT NULL,
    note VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reviews_resource FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE CASCADE,
    CONSTRAINT fk_reviews_user FOREIGN KEY (reviewer_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE chat_sessions (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT,
    chapter_id VARCHAR(64),
    title VARCHAR(200) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_session_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id)
);
CREATE INDEX idx_chat_sessions_user_updated ON chat_sessions(user_id, updated_at);

CREATE TABLE chat_messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    sources_json LONGTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_messages_session FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_chat_messages_session_created ON chat_messages(session_id, created_at);

CREATE TABLE classroom_scripts (
    id VARCHAR(64) PRIMARY KEY,
    chapter_id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    version_label VARCHAR(64) NOT NULL DEFAULT '1.0',
    review_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    script_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_classroom_script_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id)
);

CREATE TABLE classroom_sessions (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    script_id VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    summary LONGTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_classroom_session_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_classroom_session_script FOREIGN KEY (script_id) REFERENCES classroom_scripts(id)
);
CREATE INDEX idx_classroom_sessions_user_updated ON classroom_sessions(user_id, updated_at);

CREATE TABLE classroom_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    content LONGTEXT,
    from_state VARCHAR(32) NOT NULL,
    to_state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_classroom_events_session FOREIGN KEY (session_id) REFERENCES classroom_sessions(id) ON DELETE CASCADE
);

CREATE TABLE animation_records (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT,
    chapter_id VARCHAR(64),
    animation_type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    observation LONGTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_animation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_animation_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id)
);

CREATE TABLE code_runs (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT,
    chapter_id VARCHAR(64),
    language VARCHAR(32) NOT NULL,
    source_code LONGTEXT NOT NULL,
    stdin_text LONGTEXT,
    status VARCHAR(32) NOT NULL,
    output_text LONGTEXT,
    error_text LONGTEXT,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_code_run_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_code_run_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id)
);
CREATE INDEX idx_code_runs_user_created ON code_runs(user_id, created_at);

CREATE TABLE learning_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    chapter_id VARCHAR(64),
    event_type VARCHAR(48) NOT NULL,
    reference_id VARCHAR(96),
    payload_json LONGTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_learning_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id)
);
CREATE INDEX idx_learning_user_chapter_created ON learning_records(user_id, chapter_id, created_at);
