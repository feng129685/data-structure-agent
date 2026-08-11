CREATE TABLE presentation_manifests (
    id VARCHAR(96) PRIMARY KEY,
    chapter_id VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64),
    title VARCHAR(300) NOT NULL,
    source_name VARCHAR(300) NOT NULL,
    source_path VARCHAR(1000) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    version_label VARCHAR(64) NOT NULL DEFAULT '1.0',
    review_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    manifest_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_presentation_manifest_chapter
        FOREIGN KEY (chapter_id) REFERENCES chapters(id),
    CONSTRAINT fk_presentation_manifest_resource
        FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE SET NULL
);

CREATE INDEX idx_presentation_manifests_chapter_review
    ON presentation_manifests(chapter_id, review_status, updated_at);
CREATE INDEX idx_presentation_manifests_hash_version
    ON presentation_manifests(content_hash, version_label);

CREATE TABLE presentation_pages (
    id VARCHAR(120) PRIMARY KEY,
    manifest_id VARCHAR(96) NOT NULL,
    page_number INT NOT NULL,
    title VARCHAR(300) NOT NULL DEFAULT '',
    source_ref VARCHAR(160) NOT NULL,
    image_path VARCHAR(1000),
    content_hash VARCHAR(64) NOT NULL,
    raw_text LONGTEXT,
    speaker_notes LONGTEXT,
    semantic_summary LONGTEXT,
    version_label VARCHAR(64) NOT NULL DEFAULT '1.0',
    review_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    page_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_presentation_page_manifest
        FOREIGN KEY (manifest_id) REFERENCES presentation_manifests(id) ON DELETE CASCADE,
    CONSTRAINT uq_presentation_page_number UNIQUE (manifest_id, page_number),
    CONSTRAINT uq_presentation_page_source UNIQUE (manifest_id, source_ref)
);

CREATE INDEX idx_presentation_pages_manifest_review_page
    ON presentation_pages(manifest_id, review_status, page_number);
CREATE INDEX idx_presentation_pages_source_ref
    ON presentation_pages(source_ref);

ALTER TABLE classroom_events
    ADD COLUMN presentation_page_id VARCHAR(120);
ALTER TABLE classroom_events
    ADD COLUMN animation_ref VARCHAR(160);
ALTER TABLE classroom_events
    ADD COLUMN animation_record_id VARCHAR(64);

ALTER TABLE classroom_events
    ADD CONSTRAINT fk_classroom_event_presentation_page
    FOREIGN KEY (presentation_page_id) REFERENCES presentation_pages(id) ON DELETE SET NULL;
ALTER TABLE classroom_events
    ADD CONSTRAINT fk_classroom_event_animation_record
    FOREIGN KEY (animation_record_id) REFERENCES animation_records(id) ON DELETE SET NULL;

CREATE INDEX idx_classroom_events_presentation_page
    ON classroom_events(presentation_page_id, created_at);
CREATE INDEX idx_classroom_events_animation_ref
    ON classroom_events(animation_ref, created_at);
CREATE INDEX idx_classroom_events_animation_record
    ON classroom_events(animation_record_id, created_at);

CREATE TABLE dsvp_request_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    classroom_event_id BIGINT,
    animation_record_id VARCHAR(64),
    protocol_version VARCHAR(16) NOT NULL,
    request_json LONGTEXT NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'CLASSROOM',
    source_ref VARCHAR(160),
    version_label VARCHAR(64) NOT NULL DEFAULT '1.0',
    review_status VARCHAR(32) NOT NULL DEFAULT 'UNREVIEWED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dsvp_snapshot_classroom_event
        FOREIGN KEY (classroom_event_id) REFERENCES classroom_events(id) ON DELETE SET NULL,
    CONSTRAINT fk_dsvp_snapshot_animation_record
        FOREIGN KEY (animation_record_id) REFERENCES animation_records(id) ON DELETE SET NULL
);

CREATE INDEX idx_dsvp_snapshots_event_created
    ON dsvp_request_snapshots(classroom_event_id, created_at);
CREATE INDEX idx_dsvp_snapshots_animation_created
    ON dsvp_request_snapshots(animation_record_id, created_at);
CREATE INDEX idx_dsvp_snapshots_protocol_review_created
    ON dsvp_request_snapshots(protocol_version, review_status, created_at);
CREATE INDEX idx_dsvp_snapshots_source_created
    ON dsvp_request_snapshots(source_type, source_ref, created_at);

ALTER TABLE animation_observations
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'USER';
ALTER TABLE animation_observations
    ADD COLUMN source_ref VARCHAR(160);
ALTER TABLE animation_observations
    ADD COLUMN version_label VARCHAR(64) NOT NULL DEFAULT '1.0';
ALTER TABLE animation_observations
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'UNREVIEWED';

UPDATE animation_observations
SET source_type = 'LEGACY',
    source_ref = 'animation_records.observation';

CREATE INDEX idx_animation_observations_review_created
    ON animation_observations(review_status, created_at);
CREATE INDEX idx_animation_observations_source_created
    ON animation_observations(source_type, source_ref, created_at);
CREATE INDEX idx_animation_observations_user_created
    ON animation_observations(user_id, created_at);
