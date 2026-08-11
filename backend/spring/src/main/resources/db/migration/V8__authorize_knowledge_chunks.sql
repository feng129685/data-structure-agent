ALTER TABLE knowledge_chunks
    ADD COLUMN license_scope VARCHAR(32) NOT NULL DEFAULT 'TEAM_ONLY';

UPDATE knowledge_chunks
SET license_scope = COALESCE(
    (
        SELECT resources.license_scope
        FROM resources
        WHERE resources.id = knowledge_chunks.resource_id
    ),
    'CLASSROOM_ONLY'
);
