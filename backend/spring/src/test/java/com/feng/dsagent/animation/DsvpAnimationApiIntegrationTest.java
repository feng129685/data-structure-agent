package com.feng.dsagent.animation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.security.JwtTokenService;
import com.feng.dsagent.learning.LearningProgressService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class DsvpAnimationApiIntegrationTest {

    private static final long USER_ID = 8701L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LearningProgressService learningProgress;

    @BeforeEach
    void prepareUser() {
        jdbc.update("DELETE FROM dsvp_request_snapshots WHERE source_type = 'API' AND source_ref = 'test/stack'");
        jdbc.update("DELETE FROM learning_records WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)", USER_ID, "dsvp-api@example.com", "hash");
    }

    @Test
    void requiresAuthenticationAndReturnsAValidatedDsvpEnvelope() throws Exception {
        String body = """
            {"version":"1.0","structure":"stack","operation":"push","params":{"value":3,"capacity":8},"initial_state":{"data":[1,2],"metadata":{"capacity":8}},"source_ref":"test/stack","context":{"chapter_id":"03-stack-queue","source_type":"API","source_ref":"test/stack"}}
            """;

        mockMvc.perform(post("/api/v1/animations/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        String token = tokens.issue(USER_ID, "dsvp-api@example.com", Set.of("STUDENT"));
        var simulation = mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.protocol").value("dsvp/1.0"))
            .andExpect(jsonPath("$.request.structure").value("stack"))
            .andExpect(jsonPath("$.trace.trace_id").isNotEmpty())
            .andExpect(jsonPath("$.recordId").isNotEmpty())
            .andExpect(jsonPath("$.evidencePersisted").value(true))
            .andExpect(jsonPath("$.animationRecordId").isNotEmpty())
            .andExpect(jsonPath("$.resolvedChapterId").value("03-stack-queue"))
            .andExpect(jsonPath("$.matchSource").value("EXPLICIT_CHAPTER"))
            .andExpect(jsonPath("$.animationData.type").value("stack"))
            .andExpect(jsonPath("$.animationData.steps[0].op").value("push"))
            .andReturn();

        JsonNode response = objectMapper.readTree(simulation.getResponse().getContentAsString());
        String traceId = response.path("trace").path("trace_id").asText();
        String recordId = response.path("recordId").asText();
        var animationRecord = jdbc.queryForMap(
            "SELECT id, user_id, chapter_id, animation_type, payload_json FROM animation_records WHERE id = ?",
            recordId
        );
        org.assertj.core.api.Assertions.assertThat(animationRecord.get("user_id")).isEqualTo(USER_ID);
        org.assertj.core.api.Assertions.assertThat(animationRecord.get("chapter_id")).isEqualTo("03-stack-queue");
        org.assertj.core.api.Assertions.assertThat(animationRecord.get("animation_type")).isEqualTo("stack");
        org.assertj.core.api.Assertions.assertThat(animationRecord.get("payload_json").toString()).contains("\"type\":\"stack\"");

        mockMvc.perform(post("/api/v1/animations/" + recordId + "/observations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"observation\":\"观察 push 后栈顶变为 3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordId").value(recordId));

        var snapshot = jdbc.queryForMap(
            """
            SELECT id, animation_record_id, protocol_version, request_hash, source_type, source_ref, version_label, review_status, request_json
            FROM dsvp_request_snapshots
            WHERE id = ?
            """,
            traceId
        );
        org.assertj.core.api.Assertions.assertThat(snapshot.get("id")).isEqualTo(traceId);
        org.assertj.core.api.Assertions.assertThat(snapshot.get("animation_record_id")).isEqualTo(recordId);
        org.assertj.core.api.Assertions.assertThat(snapshot.get("protocol_version")).isEqualTo("dsvp/1.0");
        org.assertj.core.api.Assertions.assertThat(snapshot.get("request_hash").toString()).hasSize(64);
        org.assertj.core.api.Assertions.assertThat(snapshot.get("source_type")).isEqualTo("API");
        org.assertj.core.api.Assertions.assertThat(snapshot.get("source_ref")).isEqualTo("test/stack");
        org.assertj.core.api.Assertions.assertThat(snapshot.get("version_label")).isEqualTo("1.0");
        org.assertj.core.api.Assertions.assertThat(snapshot.get("review_status")).isEqualTo("UNREVIEWED");
        org.assertj.core.api.Assertions.assertThat(snapshot.get("request_json").toString()).contains("\"structure\":\"stack\"");

        var learning = jdbc.queryForMap(
            "SELECT event_type, chapter_id, reference_id, payload_json FROM learning_records WHERE user_id = ? AND event_type = 'ANIMATION_SIMULATION'",
            USER_ID
        );
        org.assertj.core.api.Assertions.assertThat(learning.get("event_type")).isEqualTo("ANIMATION_SIMULATION");
        org.assertj.core.api.Assertions.assertThat(learning.get("chapter_id")).isEqualTo("03-stack-queue");
        org.assertj.core.api.Assertions.assertThat(learning.get("reference_id")).isEqualTo(traceId);
        org.assertj.core.api.Assertions.assertThat(learning.get("payload_json").toString())
            .contains("\"completed\":true")
            .contains(snapshot.get("request_hash").toString());

        int snapshotsBeforeInvalidRequest = jdbc.queryForObject(
            "SELECT COUNT(*) FROM dsvp_request_snapshots WHERE source_type = 'API' AND source_ref = 'test/stack'",
            Integer.class
        );
        int learningEventsBeforeInvalidRequest = jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE user_id = ? AND event_type = 'ANIMATION_SIMULATION'",
            Integer.class,
            USER_ID
        );

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"stack","operation":"execute","params":{},"initial_state":{"data":[]}}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DSVP_OPERATION_UNSUPPORTED"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM dsvp_request_snapshots WHERE source_type = 'API' AND source_ref = 'test/stack'",
            Integer.class
        )).isEqualTo(snapshotsBeforeInvalidRequest);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE user_id = ? AND event_type = 'ANIMATION_SIMULATION'",
            Integer.class,
            USER_ID
        )).isEqualTo(learningEventsBeforeInvalidRequest);

        var chapterProgress = learningProgress.progress(USER_ID).chapters().stream()
            .filter(chapter -> chapter.chapterId().equals("03-stack-queue"))
            .findFirst()
            .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(chapterProgress.animationCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(chapterProgress.eventCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void returnsAnExplicitPreviewWhenNoChapterContextCanBeResolved() throws Exception {
        String token = tokens.issue(USER_ID, "dsvp-api@example.com", Set.of("STUDENT"));

        var simulation = mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"queue","operation":"enqueue","params":{"value":7},"initial_state":{"data":[]}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidencePersisted").value(false))
            .andExpect(jsonPath("$.recordId").doesNotExist())
            .andExpect(jsonPath("$.animationRecordId").doesNotExist())
            .andExpect(jsonPath("$.resolvedChapterId").doesNotExist())
            .andExpect(jsonPath("$.matchSource").value("NONE"))
            .andReturn();

        JsonNode response = objectMapper.readTree(simulation.getResponse().getContentAsString());
        String traceId = response.path("trace").path("trace_id").asText();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_records WHERE user_id = ? AND id = ?",
            Integer.class,
            USER_ID,
            response.path("recordId").asText()
        )).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM dsvp_request_snapshots WHERE id = ?",
            Integer.class,
            traceId
        )).isZero();
    }

    @Test
    void rejectsClientCompletionClaimsBeforeAnyEvidenceIsRecorded() throws Exception {
        String token = tokens.issue(USER_ID, "dsvp-api@example.com", Set.of("STUDENT"));

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"stack","operation":"push","params":{"value":3},"initial_state":{"data":[]},"completed":true}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DSVP_UNEXPECTED_FIELD"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM dsvp_request_snapshots WHERE source_type = 'API'",
            Integer.class
        )).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE user_id = ? AND event_type = 'ANIMATION_SIMULATION'",
            Integer.class,
            USER_ID
        )).isZero();
    }
}
