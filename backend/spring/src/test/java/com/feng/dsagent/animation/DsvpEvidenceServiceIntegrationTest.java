package com.feng.dsagent.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class DsvpEvidenceServiceIntegrationTest {

    private static final long USER_ID = 8711L;
    private static final long MISSING_USER_ID = 8712L;
    private static final long SECOND_USER_ID = 8713L;

    @Autowired
    private DsvpEvidenceService evidence;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void prepareData() {
        jdbc.update("DELETE FROM classroom_events WHERE session_id = 'dsvp-evidence-session'");
        jdbc.update("DELETE FROM classroom_sessions WHERE id = 'dsvp-evidence-session'");
        jdbc.update("DELETE FROM classroom_scripts WHERE id = 'dsvp-evidence-script'");
        jdbc.update("DELETE FROM presentation_pages WHERE id = 'dsvp-evidence-page'");
        jdbc.update("DELETE FROM presentation_manifests WHERE id = 'dsvp-evidence-presentation'");
        jdbc.update("DELETE FROM learning_records WHERE user_id IN (?, ?)", USER_ID, SECOND_USER_ID);
        jdbc.update(
            "DELETE FROM dsvp_request_snapshots WHERE source_ref IN (?, ?, ?, ?, ?, ?)",
            "classroom_session:dsvp-evidence-session",
            "ppt/evidence/1",
            "service/rollback",
            "retry/evidence",
            "api/v1/animations/simulate",
            "multi-user/evidence"
        );
        jdbc.update("DELETE FROM animation_records WHERE user_id IN (?, ?)", USER_ID, SECOND_USER_ID);
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (?, ?)", USER_ID, SECOND_USER_ID);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", USER_ID, SECOND_USER_ID);
        jdbc.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            USER_ID,
            "dsvp-evidence-service@example.com",
            "hash"
        );
        jdbc.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            SECOND_USER_ID,
            "dsvp-evidence-second@example.com",
            "hash"
        );
        jdbc.update(
            """
            INSERT INTO classroom_scripts (id, chapter_id, title, version_label, review_status, script_json)
            VALUES ('dsvp-evidence-script', '03-stack-queue', 'Evidence classroom', '1.0', 'PUBLISHED', ?)
            """,
            "{\"lessonId\":\"evidence-lesson\",\"chapterId\":\"03-stack-queue\"}"
        );
        jdbc.update(
            """
            INSERT INTO classroom_sessions (
                id, user_id, script_id, state, paused, summary, script_json_snapshot, chapter_id_snapshot
            ) VALUES ('dsvp-evidence-session', ?, 'dsvp-evidence-script', 'OPENING', FALSE, NULL, ?, '03-stack-queue')
            """,
            USER_ID,
            "{\"lessonId\":\"evidence-lesson\",\"chapterId\":\"03-stack-queue\"}"
        );
        jdbc.update(
            """
            INSERT INTO presentation_manifests (
                id, chapter_id, resource_id, title, source_name, source_path, content_hash,
                version_label, review_status, manifest_json
            ) VALUES ('dsvp-evidence-presentation', '02-linear-list', NULL, 'Evidence PPT', 'evidence.pptx',
                'presentation/evidence.pptx', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                '1.0', 'PUBLISHED', ?)
            """,
            "{\"presentationId\":\"dsvp-evidence-presentation\"}"
        );
        jdbc.update(
            """
            INSERT INTO presentation_pages (
                id, manifest_id, page_number, title, source_ref, image_path, content_hash, raw_text,
                speaker_notes, semantic_summary, version_label, review_status, page_json
            ) VALUES ('dsvp-evidence-page', 'dsvp-evidence-presentation', 1, 'Evidence page', 'ppt/evidence/1',
                'presentation/evidence/1.png', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                '线性表页面', NULL, NULL, '1.0', 'PUBLISHED', ?)
            """,
            "{\"pageNumber\":1}"
        );
    }

    @Test
    void recordsServerSelectedClassroomAndPresentationProvenance() throws Exception {
        evidence.simulate(USER_ID, objectMapper.readTree("""
            {"version":"1.0","structure":"stack","operation":"push","params":{"value":3},"initial_state":{"data":[]},"context":{"classroom_session_id":"dsvp-evidence-session","source_type":"CLASSROOM"}}
            """), DsvpEvidenceSource.CLASSROOM);
        evidence.simulate(USER_ID, objectMapper.readTree("""
            {"version":"1.0","structure":"queue","operation":"enqueue","params":{"value":3},"initial_state":{"data":[]},"context":{"presentation_id":"dsvp-evidence-presentation","presentation_page_id":"dsvp-evidence-page","source_type":"PPT"}}
            """), DsvpEvidenceSource.PPT);

        List<Map<String, Object>> snapshots = jdbc.queryForList(
            """
            SELECT source_type, source_ref, animation_record_id, protocol_version, version_label, review_status
            FROM dsvp_request_snapshots
            WHERE source_ref IN ('classroom_session:dsvp-evidence-session', 'ppt/evidence/1')
            ORDER BY source_type
            """
        );
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0))
            .containsEntry("source_type", "CLASSROOM")
            .containsEntry("source_ref", "classroom_session:dsvp-evidence-session")
            .containsEntry("protocol_version", "dsvp/1.0")
            .containsEntry("version_label", "1.0")
            .containsEntry("review_status", "UNREVIEWED");
        assertThat(snapshots.get(1))
            .containsEntry("source_type", "PPT")
            .containsEntry("source_ref", "ppt/evidence/1");
        assertThat(snapshots).allSatisfy(snapshot -> assertThat(snapshot.get("animation_record_id")).isNotNull());
        assertThat(jdbc.queryForList(
            "SELECT chapter_id FROM animation_records WHERE user_id = ? ORDER BY created_at",
            USER_ID
        )).extracting(row -> row.get("chapter_id"))
            .containsExactly("03-stack-queue", "02-linear-list");
        assertThat(jdbc.queryForList(
            "SELECT chapter_id FROM learning_records WHERE user_id = ? AND event_type = 'ANIMATION_SIMULATION' ORDER BY id",
            USER_ID
        )).extracting(row -> row.get("chapter_id"))
            .containsExactly("03-stack-queue", "02-linear-list");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE user_id = ? AND event_type = 'ANIMATION_SIMULATION'",
            Integer.class,
            USER_ID
        )).isEqualTo(2);
    }

    @Test
    void rejectsAChapterConflictBeforePersistingAnyEvidence() throws Exception {
        var request = objectMapper.readTree("""
            {"version":"1.0","structure":"stack","operation":"push","params":{"value":4},"initial_state":{"data":[]},"context":{"classroom_session_id":"dsvp-evidence-session","chapter_id":"02-linear-list","source_type":"CLASSROOM"}}
            """);

        assertThatThrownBy(() -> evidence.simulate(USER_ID, request, DsvpEvidenceSource.CLASSROOM))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status().value()).isEqualTo(409);
                assertThat(error.code()).isEqualTo("DSVP_CHAPTER_CONFLICT");
            });
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_records WHERE user_id = ?", Integer.class, USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE user_id = ?", Integer.class, USER_ID
        )).isZero();
    }

    @Test
    void reusesTheSameEvidenceForAnIdenticalRetry() throws Exception {
        var request = objectMapper.readTree("""
            {"version":"1.0","structure":"stack","operation":"push","params":{"value":5},"initial_state":{"data":[]},"context":{"chapter_id":"03-stack-queue","source_type":"API","source_ref":"retry/evidence"}}
            """);

        DsvpSimulationResponse first = evidence.simulate(USER_ID, request, DsvpEvidenceSource.API);
        DsvpSimulationResponse retry = evidence.simulate(USER_ID, request, DsvpEvidenceSource.API);

        assertThat(retry.recordId()).isEqualTo(first.recordId());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_records WHERE user_id = ?", Integer.class, USER_ID
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM dsvp_request_snapshots", Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE user_id = ? AND event_type = 'ANIMATION_SIMULATION'",
            Integer.class,
            USER_ID
        )).isEqualTo(1);
    }

    @Test
    void scopesDeterministicEvidenceIdsToTheAuthenticatedUser() throws Exception {
        var request = objectMapper.readTree("""
            {"version":"1.0","structure":"queue","operation":"enqueue","params":{"value":8},"initial_state":{"data":[]},"context":{"chapter_id":"03-stack-queue","source_type":"API","source_ref":"multi-user/evidence"}}
            """);

        DsvpSimulationResponse firstUser = evidence.simulate(USER_ID, request, DsvpEvidenceSource.API);
        DsvpSimulationResponse secondUser = evidence.simulate(SECOND_USER_ID, request, DsvpEvidenceSource.API);

        assertThat(firstUser.trace().path("trace_id").asText())
            .isNotEqualTo(secondUser.trace().path("trace_id").asText());
        assertThat(firstUser.recordId()).isNotEqualTo(secondUser.recordId());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM dsvp_request_snapshots WHERE source_ref = 'multi-user/evidence'",
            Integer.class
        )).isEqualTo(2);
    }

    @Test
    void rejectsAnotherUsersClassroomSessionWithoutDisclosingIt() throws Exception {
        var request = objectMapper.readTree("""
            {"version":"1.0","structure":"stack","operation":"peek","params":{},"initial_state":{"data":[1]},"context":{"classroom_session_id":"dsvp-evidence-session","source_type":"CLASSROOM"}}
            """);

        assertThatThrownBy(() -> evidence.simulate(SECOND_USER_ID, request, DsvpEvidenceSource.CLASSROOM))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status().value()).isEqualTo(403);
                assertThat(error.code()).isEqualTo("DSVP_SOURCE_FORBIDDEN");
            });
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_records WHERE user_id = ?", Integer.class, SECOND_USER_ID
        )).isZero();
    }

    @Test
    void rollsBackTheSnapshotWhenTheLearningEvidenceCannotBeRecorded() throws Exception {
        var request = objectMapper.readTree("""
            {"version":"1.0","structure":"array","operation":"set","params":{"value":9,"index":0},"initial_state":{"data":[1]},"source_ref":"service/rollback","context":{"chapter_id":"03-stack-queue","source_type":"API","source_ref":"service/rollback"}}
            """);

        assertThatThrownBy(() -> evidence.simulate(MISSING_USER_ID, request, DsvpEvidenceSource.API))
            .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM dsvp_request_snapshots WHERE source_ref = 'service/rollback'",
            Integer.class
        )).isZero();
    }
}
