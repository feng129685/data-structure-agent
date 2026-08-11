package com.feng.dsagent.compiler;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCodeRunRepository implements CodeRunRepository {

    private final JdbcTemplate jdbc;

    JdbcCodeRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String save(long userId, String chapterId, RunCodeRequest request, RunCodeResponse response) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO code_runs "
                + "(id, user_id, chapter_id, language, source_code, stdin_text, status, output_text, error_text, duration_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            userId,
            blankToNull(chapterId),
            response.language(),
            request.code(),
            request.stdin(),
            response.status(),
            response.stdout(),
            response.stderr(),
            response.durationMs()
        );
        return id;
    }

    @Override
    public boolean chapterExists(String chapterId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM chapters WHERE id = ? AND status = 'PUBLISHED'",
            Integer.class,
            chapterId
        );
        return count != null && count > 0;
    }

    @Override
    public java.util.Optional<CodeRunSnapshot> findOwned(String runId, long userId) {
        return jdbc.query(
            "SELECT id, chapter_id, language, source_code, stdin_text, output_text, error_text, status "
                + "FROM code_runs WHERE id = ? AND user_id = ?",
            (row, index) -> new CodeRunSnapshot(
                row.getString("id"),
                row.getString("chapter_id"),
                row.getString("language"),
                row.getString("source_code"),
                row.getString("stdin_text"),
                row.getString("output_text"),
                row.getString("error_text"),
                row.getString("status")
            ),
            runId,
            userId
        ).stream().findFirst();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
