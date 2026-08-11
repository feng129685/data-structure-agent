UPDATE classroom_sessions
SET script_json_snapshot = (
    SELECT classroom_scripts.script_json
    FROM classroom_scripts
    WHERE classroom_scripts.id = classroom_sessions.script_id
)
WHERE script_json_snapshot IS NULL;
