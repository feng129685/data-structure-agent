UPDATE dsvp_request_snapshots
SET review_status = 'LEGACY_UNVERIFIED'
WHERE review_status = 'UNREVIEWED';

UPDATE animation_observations
SET review_status = 'LEGACY_UNVERIFIED'
WHERE review_status = 'UNREVIEWED';

CREATE TABLE content_review_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content_type VARCHAR(64) NOT NULL,
    content_id VARCHAR(160) NOT NULL,
    previous_status VARCHAR(32) NOT NULL,
    next_status VARCHAR(32) NOT NULL,
    note VARCHAR(2000) NOT NULL DEFAULT '',
    reviewer_user_id BIGINT NULL,
    request_id VARCHAR(128) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_content_review_events_reviewer
        FOREIGN KEY (reviewer_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_content_review_events_content_created
    ON content_review_events(content_type, content_id, created_at DESC);
CREATE INDEX idx_content_review_events_reviewer_created
    ON content_review_events(reviewer_user_id, created_at DESC);

INSERT INTO content_review_events (
    content_type,
    content_id,
    previous_status,
    next_status,
    note,
    reviewer_user_id,
    request_id,
    created_at
)
SELECT
    'RESOURCE',
    resource_id,
    'LEGACY_UNVERIFIED',
    review_status,
    note,
    reviewer_user_id,
    'legacy-content-reviews',
    created_at
FROM content_reviews;
