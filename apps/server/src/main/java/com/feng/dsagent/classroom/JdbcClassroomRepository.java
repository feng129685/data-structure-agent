package com.feng.dsagent.classroom;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcClassroomRepository implements ClassroomRepository {

    private final JdbcTemplate jdbc;

    JdbcClassroomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ClassroomScript> findPublishedScripts(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return jdbc.query(
                "SELECT id, chapter_id, title, version_label, script_json FROM classroom_scripts "
                    + "WHERE review_status = 'PUBLISHED' ORDER BY chapter_id, title, id",
                (row, index) -> script(row)
            );
        }
        return jdbc.query(
            "SELECT id, chapter_id, title, version_label, script_json FROM classroom_scripts "
                + "WHERE review_status = 'PUBLISHED' AND chapter_id = ? ORDER BY title, id",
            (row, index) -> script(row),
            chapterId
        );
    }

    @Override
    public Optional<ClassroomScript> findPublishedScript(String id) {
        return jdbc.query(
            "SELECT id, chapter_id, title, version_label, script_json FROM classroom_scripts "
                + "WHERE id = ? AND review_status = 'PUBLISHED'",
            (row, index) -> script(row),
            id
        ).stream().findFirst();
    }

    @Override
    public ClassroomSessionRecord createSession(long userId, ClassroomScript script, ClassroomStatus status) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO classroom_sessions "
                + "(id, user_id, script_id, state, paused, script_json_snapshot, chapter_id_snapshot) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id,
            userId,
            script.id(),
            status.state().name(),
            status.paused(),
            script.scriptJson(),
            script.chapterId()
        );
        return new ClassroomSessionRecord(
            id, userId, script.id(), script.chapterId(), status.state(), status.paused(), null, script.scriptJson()
        );
    }

    @Override
    public Optional<ClassroomSessionRecord> findSession(String id, long userId) {
        return jdbc.query(
            "SELECT s.id, s.user_id, s.script_id, "
                + "COALESCE(s.chapter_id_snapshot, c.chapter_id) AS chapter_id, "
                + "s.state, s.paused, s.summary, "
                + "COALESCE(s.script_json_snapshot, c.script_json) AS script_json "
                + "FROM classroom_sessions s JOIN classroom_scripts c ON c.id = s.script_id "
                + "WHERE s.id = ? AND s.user_id = ? FOR UPDATE",
            (row, index) -> new ClassroomSessionRecord(
                row.getString("id"),
                row.getLong("user_id"),
                row.getString("script_id"),
                row.getString("chapter_id"),
                ClassroomState.valueOf(row.getString("state")),
                row.getBoolean("paused"),
                row.getString("summary"),
                row.getString("script_json")
            ),
            id,
            userId
        ).stream().findFirst();
    }

    @Override
    public ClassroomSessionRecord updateSession(
        ClassroomSessionRecord session,
        ClassroomStatus status,
        String summary
    ) {
        jdbc.update(
            "UPDATE classroom_sessions SET state = ?, paused = ?, summary = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND user_id = ?",
            status.state().name(),
            status.paused(),
            summary,
            session.id(),
            session.userId()
        );
        return new ClassroomSessionRecord(
            session.id(), session.userId(), session.scriptId(), session.chapterId(), status.state(), status.paused(), summary,
            session.scriptJson()
        );
    }

    @Override
    public void appendEvent(ClassroomEventRecord event) {
        jdbc.update(
            "INSERT INTO classroom_events "
                + "(session_id, action, content, from_state, to_state, answer_status, misconception, feedback) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            event.sessionId(),
            event.action().name(),
            event.content(),
            event.fromState().name(),
            event.toState().name(),
            event.answerEvaluation() == null ? null : event.answerEvaluation().status().name(),
            event.answerEvaluation() == null ? null : event.answerEvaluation().misconception(),
            event.answerEvaluation() == null ? null : event.answerEvaluation().feedback()
        );
    }

    private ClassroomScript script(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ClassroomScript(
            row.getString("id"),
            row.getString("chapter_id"),
            row.getString("title"),
            row.getString("version_label"),
            row.getString("script_json")
        );
    }
}
