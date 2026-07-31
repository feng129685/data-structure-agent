ALTER TABLE classroom_sessions
    ADD COLUMN chapter_id_snapshot VARCHAR(64);

UPDATE classroom_sessions
SET chapter_id_snapshot = (
    SELECT classroom_scripts.chapter_id
    FROM classroom_scripts
    WHERE classroom_scripts.id = classroom_sessions.script_id
)
WHERE chapter_id_snapshot IS NULL;

ALTER TABLE classroom_sessions
    ADD CONSTRAINT fk_classroom_session_chapter_snapshot
    FOREIGN KEY (chapter_id_snapshot) REFERENCES chapters(id);
