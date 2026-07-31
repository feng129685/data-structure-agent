package com.feng.dsagent.learning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LearningProgressIntegrationTest {

    private static final long USER_ID = 9001L;

    @Autowired
    private LearningProgressService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareLearningData() {
        jdbc.update("DELETE FROM learning_records WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM code_runs WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM animation_records WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM classroom_events WHERE session_id IN (SELECT id FROM classroom_sessions WHERE user_id = ?)", USER_ID);
        jdbc.update("DELETE FROM classroom_sessions WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM classroom_scripts WHERE id = 'progress-snapshot-script'");
        jdbc.update("DELETE FROM chat_messages WHERE session_id IN (SELECT id FROM chat_sessions WHERE user_id = ?)", USER_ID);
        jdbc.update("DELETE FROM chat_sessions WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)", USER_ID, "progress@example.com", "hash");

        jdbc.update(
            "INSERT INTO chat_sessions (id, user_id, chapter_id, title) VALUES ('progress-chat', ?, '03-stack-queue', '栈')",
            USER_ID
        );
        jdbc.update(
            "INSERT INTO animation_records "
                + "(id, user_id, chapter_id, animation_type, title, payload_json) "
                + "VALUES ('progress-animation', ?, '03-stack-queue', 'stack', '栈动画', '{}')",
            USER_ID
        );
        jdbc.update(
            "INSERT INTO code_runs (id, user_id, chapter_id, language, source_code, status) "
                + "VALUES ('progress-code', ?, '03-stack-queue', 'c', 'int main(){}', 'SUCCESS')",
            USER_ID
        );
        jdbc.update(
            "INSERT INTO learning_records (user_id, chapter_id, event_type) VALUES (?, '03-stack-queue', 'RESOURCE_VIEW')",
            USER_ID
        );
    }

    @Test
    void aggregatesActivitiesByChapter() {
        LearningProgressView progress = service.progress(USER_ID);

        assertThat(progress.totalActivities()).isEqualTo(4);
        assertThat(progress.chapters())
            .filteredOn(chapter -> chapter.chapterId().equals("03-stack-queue"))
            .singleElement()
            .satisfies(chapter -> {
                assertThat(chapter.chatCount()).isEqualTo(1);
                assertThat(chapter.animationCount()).isEqualTo(1);
                assertThat(chapter.codeRunCount()).isEqualTo(1);
                assertThat(chapter.eventCount()).isEqualTo(1);
                assertThat(chapter.totalActivities()).isEqualTo(4);
                assertThat(chapter.lastActivityAt()).isNotNull();
            });
    }

    @Test
    void keepsClassroomActivityInTheSessionChapterAfterTheScriptMoves() {
        jdbc.update(
            """
            INSERT INTO classroom_scripts (
                id, chapter_id, title, version_label, review_status, script_json
            ) VALUES ('progress-snapshot-script', '03-stack-queue', '快照课堂', '1.0', 'PUBLISHED', '{}')
            """
        );
        jdbc.update(
            """
            INSERT INTO classroom_sessions (
                id, user_id, script_id, state, paused, script_json_snapshot, chapter_id_snapshot
            ) VALUES ('progress-classroom', ?, 'progress-snapshot-script', 'OPENING', FALSE, '{}', '03-stack-queue')
            """,
            USER_ID
        );
        jdbc.update(
            "UPDATE classroom_scripts SET chapter_id = '02-linear-list' WHERE id = 'progress-snapshot-script'"
        );

        LearningProgressView progress = service.progress(USER_ID);

        assertThat(progress.chapters())
            .filteredOn(chapter -> chapter.chapterId().equals("03-stack-queue"))
            .singleElement()
            .satisfies(chapter -> assertThat(chapter.classroomCount()).isEqualTo(1));
        assertThat(progress.chapters())
            .filteredOn(chapter -> chapter.chapterId().equals("02-linear-list"))
            .singleElement()
            .satisfies(chapter -> assertThat(chapter.classroomCount()).isZero());
    }
}
