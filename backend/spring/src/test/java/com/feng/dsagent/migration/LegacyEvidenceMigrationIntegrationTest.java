package com.feng.dsagent.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LegacyEvidenceMigrationIntegrationTest {

    @Test
    void backfillsLegacyClassroomAndAnimationEvidenceWithoutDuplicatingExistingHistory() {
        String databaseName = "legacy-evidence-" + UUID.randomUUID();
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        migrate(dataSource, MigrationVersion.fromVersion("4"));
        jdbc.update(
            "INSERT INTO chapters (id, chapter_number, title, summary, status) VALUES ('legacy-chapter', 90, 'Legacy', '', 'PUBLISHED')"
        );
        jdbc.update(
            "INSERT INTO users (email, password_hash) VALUES ('legacy@example.com', 'hash')"
        );
        Long userId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = 'legacy@example.com'",
            Long.class
        );
        jdbc.update(
            """
            INSERT INTO classroom_scripts (
                id, chapter_id, title, version_label, review_status, script_json
            ) VALUES ('legacy-script', 'legacy-chapter', 'Legacy script', '1.0', 'PUBLISHED', '{"version":"legacy"}')
            """
        );
        jdbc.update(
            """
            INSERT INTO classroom_sessions (id, user_id, script_id, state, paused)
            VALUES ('legacy-session', ?, 'legacy-script', 'OPENING', FALSE)
            """,
            userId
        );
        insertAnimation(jdbc, "legacy-animation-copy", userId, "copy this observation");
        insertAnimation(jdbc, "legacy-animation-existing", userId, "already appended");
        insertAnimation(jdbc, "legacy-animation-blank", userId, "   ");
        insertAnimation(jdbc, "legacy-animation-anonymous", null, "cannot attribute this");

        migrate(dataSource, MigrationVersion.fromVersion("8"));
        jdbc.update(
            """
            INSERT INTO animation_observations (animation_record_id, user_id, observation)
            VALUES ('legacy-animation-existing', ?, 'already appended')
            """,
            userId
        );

        migrate(dataSource, null);

        assertThat(jdbc.queryForObject(
            "SELECT script_json_snapshot FROM classroom_sessions WHERE id = 'legacy-session'",
            String.class
        )).isEqualTo("{\"version\":\"legacy\"}");
        assertThat(jdbc.queryForObject(
            "SELECT chapter_id_snapshot FROM classroom_sessions WHERE id = 'legacy-session'",
            String.class
        )).isEqualTo("legacy-chapter");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_observations WHERE animation_record_id = 'legacy-animation-copy'",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_observations WHERE animation_record_id = 'legacy-animation-existing'",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_observations WHERE animation_record_id IN ('legacy-animation-blank', 'legacy-animation-anonymous')",
            Integer.class
        )).isZero();
    }

    private void migrate(DriverManagerDataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void insertAnimation(JdbcTemplate jdbc, String id, Long userId, String observation) {
        jdbc.update(
            """
            INSERT INTO animation_records (
                id, user_id, chapter_id, animation_type, title, payload_json, observation
            ) VALUES (?, ?, 'legacy-chapter', 'STACK', ?, '{}', ?)
            """,
            id,
            userId,
            id,
            observation
        );
    }
}
