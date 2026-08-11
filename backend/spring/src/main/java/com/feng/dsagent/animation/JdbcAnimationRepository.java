package com.feng.dsagent.animation;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAnimationRepository implements AnimationRepository, AnimationObservationRepository {

    private final JdbcTemplate jdbc;

    JdbcAnimationRepository(JdbcTemplate jdbc) {
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
    public String save(long userId, String chapterId, AnimationDefinition definition, String payloadJson) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO animation_records "
                + "(id, user_id, chapter_id, animation_type, title, payload_json) VALUES (?, ?, ?, ?, ?, ?)",
            id,
            userId,
            blankToNull(chapterId),
            definition.type(),
            definition.title(),
            payloadJson
        );
        return id;
    }

    @Override
    public java.util.Optional<AnimationRecord> findOwned(long userId, String recordId) {
        return jdbc.query(
            "SELECT id, chapter_id FROM animation_records WHERE id = ? AND user_id = ?",
            (row, index) -> new AnimationRecord(row.getString("id"), row.getString("chapter_id")),
            recordId,
            userId
        ).stream().findFirst();
    }

    @Override
    public void appendObservation(long userId, String recordId, String observation) {
        jdbc.update(
            "INSERT INTO animation_observations (animation_record_id, user_id, observation) VALUES (?, ?, ?)",
            recordId,
            userId,
            observation
        );
        jdbc.update(
            "UPDATE animation_records SET observation = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?",
            observation,
            recordId,
            userId
        );
    }

    @Override
    public void appendObservation(
        long userId,
        String recordId,
        String observation,
        String sourceType,
        String sourceRef,
        String versionLabel
    ) {
        jdbc.update(
            "INSERT INTO animation_observations "
                + "(animation_record_id, user_id, observation, source_type, source_ref, version_label, review_status) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'UNREVIEWED')",
            recordId,
            userId,
            observation,
            sourceType,
            sourceRef,
            versionLabel
        );
        jdbc.update(
            "UPDATE animation_records SET observation = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?",
            observation,
            recordId,
            userId
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
