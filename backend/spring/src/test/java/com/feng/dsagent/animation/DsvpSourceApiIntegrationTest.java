package com.feng.dsagent.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.security.JwtTokenService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DsvpSourceApiIntegrationTest {

    private static final long OWNER = 8731L;
    private static final long OTHER_USER = 8732L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @BeforeEach
    void prepareSources() {
        jdbc.update("DELETE FROM dsvp_request_snapshots WHERE source_ref IN (?, ?, ?)",
            "classroom_session:dsvp-source-session", "ppt/source/1", "dsvp-source-animation");
        jdbc.update("DELETE FROM learning_records WHERE user_id IN (?, ?)", OWNER, OTHER_USER);
        jdbc.update("DELETE FROM animation_records WHERE user_id IN (?, ?)", OWNER, OTHER_USER);
        jdbc.update("DELETE FROM classroom_events WHERE session_id = 'dsvp-source-session'");
        jdbc.update("DELETE FROM classroom_sessions WHERE id = 'dsvp-source-session'");
        jdbc.update("DELETE FROM classroom_scripts WHERE id = 'dsvp-source-script'");
        jdbc.update("DELETE FROM presentation_pages WHERE id = 'dsvp-source-page'");
        jdbc.update("DELETE FROM presentation_manifests WHERE id = 'dsvp-source-presentation'");
        jdbc.update("DELETE FROM resources WHERE id = 'dsvp-source-team-resource'");
        jdbc.update("DELETE FROM resources WHERE id = 'dsvp-source-animation'");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (?, ?)", OWNER, OTHER_USER);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", OWNER, OTHER_USER);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'hash')",
            OWNER, "dsvp-source-owner@example.com");
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'hash')",
            OTHER_USER, "dsvp-source-other@example.com");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'TEACHER')", OTHER_USER);
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, source_name,
                version_label, review_status, license_scope
            ) VALUES ('dsvp-source-team-resource', '02-linear-list', 'PPT', 'Team PPT', '', 'source.pptx',
                '1.0', 'PUBLISHED', 'TEAM_ONLY')
            """
        );
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, source_name,
                version_label, review_status, license_scope
            ) VALUES ('dsvp-source-animation', '02-linear-list', 'ANIMATION', 'Reviewed animation', '',
                'animation-definition.json', '1.0', 'PUBLISHED', 'PUBLIC')
            """
        );
        jdbc.update(
            """
            INSERT INTO classroom_scripts (id, chapter_id, title, version_label, review_status, script_json)
            VALUES ('dsvp-source-script', '03-stack-queue', 'Source classroom', '1.0', 'PUBLISHED', '{}')
            """
        );
        jdbc.update(
            """
            INSERT INTO classroom_sessions (
                id, user_id, script_id, state, paused, script_json_snapshot, chapter_id_snapshot
            ) VALUES ('dsvp-source-session', ?, 'dsvp-source-script', 'OPENING', FALSE, '{}', '03-stack-queue')
            """,
            OWNER
        );
        jdbc.update(
            """
            INSERT INTO presentation_manifests (
                id, chapter_id, title, source_name, source_path, content_hash,
                version_label, review_status, manifest_json
            ) VALUES ('dsvp-source-presentation', '02-linear-list', 'Source PPT', 'source.pptx',
                'presentation/source.pptx', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                '1.0', 'PUBLISHED', '{}')
            """
        );
        jdbc.update(
            """
            INSERT INTO presentation_pages (
                id, manifest_id, page_number, title, source_ref, content_hash,
                version_label, review_status, page_json
            ) VALUES ('dsvp-source-page', 'dsvp-source-presentation', 1, 'Source page', 'ppt/source/1',
                'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                '1.0', 'PUBLISHED', '{}')
            """
        );
    }

    @Test
    void persistsOwnedClassroomAndPublishedPresentationEvidence() throws Exception {
        String token = tokens.issue(OWNER, "dsvp-source-owner@example.com", Set.of("STUDENT"));

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"stack","operation":"peek","params":{},"initial_state":{"data":[1]},"context":{"classroom_session_id":"dsvp-source-session","source_type":"CLASSROOM"}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidencePersisted").value(true))
            .andExpect(jsonPath("$.resolvedChapterId").value("03-stack-queue"))
            .andExpect(jsonPath("$.matchSource").value("CLASSROOM_SESSION"));

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"linked_list","operation":"append","params":{"value":2},"initial_state":{"data":[1]},"context":{"presentation_id":"dsvp-source-presentation","presentation_page_id":"dsvp-source-page","source_type":"PPT"}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidencePersisted").value(true))
            .andExpect(jsonPath("$.resolvedChapterId").value("02-linear-list"))
            .andExpect(jsonPath("$.matchSource").value("PRESENTATION_PAGE"));

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM dsvp_request_snapshots WHERE animation_record_id IS NOT NULL "
                + "AND source_ref IN ('classroom_session:dsvp-source-session', 'ppt/source/1')",
            Integer.class
        )).isEqualTo(2);
    }

    @Test
    void rejectsForeignAndConflictingSourcesWithoutPersistingEvidence() throws Exception {
        String foreignToken = tokens.issue(OTHER_USER, "dsvp-source-other@example.com", Set.of("STUDENT"));
        String ownerToken = tokens.issue(OWNER, "dsvp-source-owner@example.com", Set.of("STUDENT"));

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + foreignToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"stack","operation":"peek","params":{},"initial_state":{"data":[1]},"context":{"classroom_session_id":"dsvp-source-session","source_type":"CLASSROOM"}}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("DSVP_SOURCE_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"stack","operation":"peek","params":{},"initial_state":{"data":[1]},"context":{"classroom_session_id":"dsvp-source-session","chapter_id":"02-linear-list","source_type":"CLASSROOM"}}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DSVP_CHAPTER_CONFLICT"));

        jdbc.update("UPDATE presentation_pages SET review_status = 'DRAFT' WHERE id = 'dsvp-source-page'");
        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"linked_list","operation":"append","params":{"value":2},"initial_state":{"data":[1]},"context":{"presentation_id":"dsvp-source-presentation","presentation_page_id":"dsvp-source-page","source_type":"PPT"}}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("DSVP_SOURCE_FORBIDDEN"));

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_records WHERE user_id IN (?, ?)",
            Integer.class,
            OWNER,
            OTHER_USER
        )).isZero();
    }

    @Test
    void enforcesPresentationAudienceBeforeCreatingEvidence() throws Exception {
        jdbc.update(
            "UPDATE presentation_manifests SET resource_id = 'dsvp-source-team-resource' "
                + "WHERE id = 'dsvp-source-presentation'"
        );
        String studentToken = tokens.issue(OWNER, "dsvp-source-owner@example.com", Set.of("STUDENT"));
        String teacherToken = tokens.issue(OTHER_USER, "dsvp-source-other@example.com", Set.of("TEACHER"));
        String body = """
            {"version":"1.0","structure":"linked_list","operation":"append","params":{"value":2},"initial_state":{"data":[1]},"context":{"presentation_id":"dsvp-source-presentation","presentation_page_id":"dsvp-source-page","source_type":"PPT"}}
            """;

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("DSVP_SOURCE_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + teacherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resolvedChapterId").value("02-linear-list"))
            .andExpect(jsonPath("$.evidencePersisted").value(true));
    }

    @Test
    void resolvesAReviewedAnimationDefinitionWhenNoStrongerSourceExists() throws Exception {
        String token = tokens.issue(OWNER, "dsvp-source-owner@example.com", Set.of("STUDENT"));

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"linked_list","operation":"append","params":{"value":2},"initial_state":{"data":[1]},"source_ref":"dsvp-source-animation","context":{"source_type":"API","source_ref":"dsvp-source-animation"}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidencePersisted").value(true))
            .andExpect(jsonPath("$.resolvedChapterId").value("02-linear-list"))
            .andExpect(jsonPath("$.matchSource").value("ANIMATION_DEFINITION"));
    }
}
