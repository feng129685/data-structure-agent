package com.feng.dsagent.learning;

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
class LearningActivityApiIntegrationTest {

    private static final long USER_ID = 8601L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @BeforeEach
    void prepareData() {
        jdbc.update("DELETE FROM learning_records WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM animation_records WHERE id = 'activity-api-animation'");
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)", USER_ID, "activity@example.com", "hash");
        jdbc.update(
            """
            INSERT INTO animation_records (id, user_id, chapter_id, animation_type, title, payload_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            "activity-api-animation", USER_ID, "03-stack-queue", "stack", "入栈", "{}"
        );
    }

    @Test
    void recordsLearningEventsAndOwnedAnimationObservations() throws Exception {
        String token = tokens.issue(USER_ID, "activity@example.com", Set.of("STUDENT"));

        mockMvc.perform(post("/api/v1/learning/events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"RESOURCE_VIEW\",\"chapterId\":\"03-stack-queue\",\"referenceId\":\"stack-pdf\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.eventType").value("RESOURCE_VIEW"));

        mockMvc.perform(post("/api/v1/animations/activity-api-animation/observations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"observation\":\"后进先出\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordId").value("activity-api-animation"));

        mockMvc.perform(post("/api/v1/animations/activity-api-animation/observations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"observation\":\"栈顶元素最先离开\"}"))
            .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT observation FROM animation_records WHERE id = 'activity-api-animation'", String.class
        )).isEqualTo("栈顶元素最先离开");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM animation_observations WHERE animation_record_id = 'activity-api-animation'",
            Integer.class
        )).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT payload_json FROM learning_records WHERE user_id = ? "
                + "AND event_type = 'ANIMATION_OBSERVATION' ORDER BY id DESC LIMIT 1",
            String.class,
            USER_ID
        )).contains("栈顶元素最先离开");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE user_id = ?", Integer.class, USER_ID
        )).isEqualTo(3);
    }

    @Test
    void rejectsClientSubmittedServerManagedEvidence() throws Exception {
        String token = tokens.issue(USER_ID, "activity@example.com", Set.of("STUDENT"));

        for (String eventType : java.util.List.of(
            "CLASSROOM_ANSWER",
            "ANIMATION_OBSERVATION",
            "CODE_REVIEW"
        )) {
            mockMvc.perform(post("/api/v1/learning/events")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"eventType":"%s","chapterId":"03-stack-queue","referenceId":"forged"}
                        """.formatted(eventType)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEARNING_EVENT_SERVER_MANAGED"));
        }

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE user_id = ?", Integer.class, USER_ID
        )).isZero();
    }
}
