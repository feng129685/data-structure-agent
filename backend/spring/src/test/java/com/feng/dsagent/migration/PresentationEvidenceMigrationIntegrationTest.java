package com.feng.dsagent.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PresentationEvidenceMigrationIntegrationTest {

    @Test
    void upgradesExistingEvidenceWithReviewedPresentationAndDsvpPersistence() {
        String databaseName = "presentation-evidence-" + UUID.randomUUID();
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        migrate(dataSource, MigrationVersion.fromVersion("10"));
        seedLegacyObservation(jdbc);
        migrate(dataSource, null);

        assertThat(tableNames(jdbc)).contains(
            "presentation_manifests",
            "presentation_pages",
            "dsvp_request_snapshots"
        );
        assertThat(columnNames(jdbc, "presentation_manifests")).contains(
            "source_name",
            "source_path",
            "content_hash",
            "version_label",
            "review_status",
            "manifest_json"
        );
        assertThat(columnNames(jdbc, "presentation_pages")).contains(
            "source_ref",
            "version_label",
            "review_status",
            "page_json"
        );
        assertThat(columnNames(jdbc, "dsvp_request_snapshots")).contains(
            "animation_record_id",
            "protocol_version",
            "request_json",
            "request_hash",
            "source_ref",
            "version_label",
            "review_status"
        );
        assertThat(columnNames(jdbc, "classroom_events")).contains(
            "presentation_page_id",
            "animation_ref",
            "animation_record_id"
        );
        assertThat(columnNames(jdbc, "animation_observations")).contains(
            "source_type",
            "source_ref",
            "version_label",
            "review_status"
        );

        assertThat(indexNames(jdbc)).contains(
            "idx_presentation_manifests_chapter_review",
            "idx_presentation_pages_manifest_review_page",
            "idx_dsvp_snapshots_animation_created",
            "idx_dsvp_snapshots_protocol_review_created",
            "idx_classroom_events_presentation_page",
            "idx_classroom_events_animation_record",
            "idx_animation_observations_review_created"
        );

        Map<String, Object> legacyObservation = jdbc.queryForMap(
            """
            SELECT source_type, source_ref, version_label, review_status
            FROM animation_observations
            WHERE observation = 'legacy note'
            """
        );
        assertThat(legacyObservation).containsEntry("source_type", "LEGACY");
        assertThat(legacyObservation).containsEntry("source_ref", "animation_records.observation");
        assertThat(legacyObservation).containsEntry("version_label", "1.0");
        assertThat(legacyObservation).containsEntry("review_status", "LEGACY_UNVERIFIED");

        assertThatThrownBy(() -> jdbc.update(
            """
            INSERT INTO dsvp_request_snapshots (
                id, animation_record_id, protocol_version, request_json, request_hash,
                source_type, source_ref, version_label, review_status
            ) VALUES ('missing-animation-snapshot', 'missing-animation', 'dsvp/1.0', '{}',
                'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                'API', 'migration/fk-check', '1.0', 'UNREVIEWED')
            """
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> tableNames(JdbcTemplate jdbc) {
        return jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class
        );
    }

    private List<String> columnNames(JdbcTemplate jdbc, String tableName) {
        return jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
            String.class,
            tableName
        );
    }

    private List<String> indexNames(JdbcTemplate jdbc) {
        return jdbc.queryForList(
            "SELECT index_name FROM information_schema.indexes WHERE table_schema = 'public'",
            String.class
        );
    }

    private void seedLegacyObservation(JdbcTemplate jdbc) {
        jdbc.update(
            "INSERT INTO chapters (id, chapter_number, title, summary) VALUES ('migration-chapter', 91, 'Migration', '')"
        );
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (9101, 'migration@example.com', 'hash')");
        jdbc.update(
            """
            INSERT INTO animation_records (
                id, user_id, chapter_id, animation_type, title, payload_json, observation
            ) VALUES ('legacy-animation', 9101, 'migration-chapter', 'STACK', 'Legacy', '{}', 'legacy note')
            """
        );
        jdbc.update(
            """
            INSERT INTO animation_observations (animation_record_id, user_id, observation)
            VALUES ('legacy-animation', 9101, 'legacy note')
            """
        );
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
}
