package com.feng.dsagent.learning;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLearningProgressRepository implements LearningProgressRepository {

    private final JdbcTemplate jdbc;

    JdbcLearningProgressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChapterLearningProgress> progress(long userId) {
        Map<String, Accumulator> chapters = new LinkedHashMap<>();
        jdbc.query(
            "SELECT id, chapter_number, title FROM chapters WHERE status = 'PUBLISHED' ORDER BY chapter_number",
            (RowCallbackHandler) row -> chapters.put(
                row.getString("id"),
                new Accumulator(row.getString("id"), row.getInt("chapter_number"), row.getString("title"))
            )
        );

        add(chapters, userId,
            "SELECT chapter_id, COUNT(*) activity_count, MAX(updated_at) last_at FROM chat_sessions "
                + "WHERE user_id = ? AND chapter_id IS NOT NULL GROUP BY chapter_id",
            ActivityType.CHAT);
        add(chapters, userId,
            "SELECT COALESCE(cs.chapter_id_snapshot, s.chapter_id) AS chapter_id, "
                + "COUNT(*) activity_count, MAX(cs.updated_at) last_at "
                + "FROM classroom_sessions cs JOIN classroom_scripts s ON s.id = cs.script_id "
                + "WHERE cs.user_id = ? GROUP BY COALESCE(cs.chapter_id_snapshot, s.chapter_id)",
            ActivityType.CLASSROOM);
        add(chapters, userId,
            "SELECT chapter_id, COUNT(*) activity_count, MAX(updated_at) last_at FROM animation_records "
                + "WHERE user_id = ? AND chapter_id IS NOT NULL GROUP BY chapter_id",
            ActivityType.ANIMATION);
        add(chapters, userId,
            "SELECT chapter_id, COUNT(*) activity_count, MAX(created_at) last_at FROM code_runs "
                + "WHERE user_id = ? AND chapter_id IS NOT NULL GROUP BY chapter_id",
            ActivityType.CODE);
        add(chapters, userId,
            "SELECT chapter_id, COUNT(*) activity_count, MAX(created_at) last_at FROM learning_records "
                + "WHERE user_id = ? AND chapter_id IS NOT NULL GROUP BY chapter_id",
            ActivityType.EVENT);

        return chapters.values().stream().map(Accumulator::view).toList();
    }

    private void add(
        Map<String, Accumulator> chapters,
        long userId,
        String sql,
        ActivityType type
    ) {
        jdbc.query(sql, (RowCallbackHandler) row -> {
            Accumulator chapter = chapters.get(row.getString("chapter_id"));
            if (chapter != null) {
                chapter.add(type, row.getLong("activity_count"), instant(row.getTimestamp("last_at")));
            }
        }, userId);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private enum ActivityType {
        CHAT,
        CLASSROOM,
        ANIMATION,
        CODE,
        EVENT
    }

    private static final class Accumulator {
        private final String chapterId;
        private final int chapterNumber;
        private final String title;
        private long chats;
        private long classrooms;
        private long animations;
        private long codeRuns;
        private long events;
        private Instant lastActivityAt;

        private Accumulator(String chapterId, int chapterNumber, String title) {
            this.chapterId = chapterId;
            this.chapterNumber = chapterNumber;
            this.title = title;
        }

        private void add(ActivityType type, long count, Instant activityAt) {
            switch (type) {
                case CHAT -> chats += count;
                case CLASSROOM -> classrooms += count;
                case ANIMATION -> animations += count;
                case CODE -> codeRuns += count;
                case EVENT -> events += count;
            }
            if (activityAt != null && (lastActivityAt == null || activityAt.isAfter(lastActivityAt))) {
                lastActivityAt = activityAt;
            }
        }

        private ChapterLearningProgress view() {
            long total = chats + classrooms + animations + codeRuns + events;
            return new ChapterLearningProgress(
                chapterId,
                chapterNumber,
                title,
                chats,
                classrooms,
                animations,
                codeRuns,
                events,
                total,
                lastActivityAt
            );
        }
    }
}
