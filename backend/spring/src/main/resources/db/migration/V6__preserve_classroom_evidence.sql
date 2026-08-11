ALTER TABLE classroom_sessions
    ADD COLUMN script_json_snapshot LONGTEXT;

ALTER TABLE classroom_events
    ADD COLUMN answer_status VARCHAR(32);

ALTER TABLE classroom_events
    ADD COLUMN misconception VARCHAR(500);

ALTER TABLE classroom_events
    ADD COLUMN feedback VARCHAR(2000);
