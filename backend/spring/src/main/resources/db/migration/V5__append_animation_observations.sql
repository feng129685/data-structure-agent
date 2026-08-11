CREATE TABLE animation_observations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    animation_record_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    observation LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_animation_observations_record
        FOREIGN KEY (animation_record_id) REFERENCES animation_records(id) ON DELETE CASCADE,
    CONSTRAINT fk_animation_observations_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_animation_observations_record_created
    ON animation_observations(animation_record_id, created_at);
