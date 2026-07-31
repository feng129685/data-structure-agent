package com.feng.dsagent.learning;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLearningEventRepository implements LearningEventRepository {

    private final JdbcTemplate jdbc;

    JdbcLearningEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isPublishedChapter(String chapterId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM chapters WHERE id = ? AND status = 'PUBLISHED'",
            Integer.class,
            chapterId
        );
        return count != null && count > 0;
    }

    @Override
    public LearningEventView save(
        long userId,
        String eventType,
        String chapterId,
        String referenceId,
        String payloadJson,
        Instant createdAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO learning_records (user_id, chapter_id, event_type, reference_id, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                new String[] {"id"}
            );
            statement.setLong(1, userId);
            statement.setString(2, chapterId);
            statement.setString(3, eventType);
            statement.setString(4, referenceId);
            statement.setString(5, payloadJson);
            statement.setTimestamp(6, Timestamp.from(createdAt));
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Learning event did not return a generated id");
        }
        return new LearningEventView(id.longValue(), eventType, chapterId, referenceId, createdAt);
    }
}
