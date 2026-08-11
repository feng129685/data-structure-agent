package com.feng.dsagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.feng.dsagent.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class NodeTokenCompatibilityApiIntegrationTest {

    private static final long SPRING_USER_ID = 8611L;
    private static final long NODE_USER_ID = 31L;
    private static final String EMAIL = "node-token-bridge@example.com";
    private static final String TEST_SECRET = "node-compat-test-secret-with-at-least-32-characters";
    private static final String SOURCE_REF = "node-token-bridge";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService springTokens;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void prepareUser() {
        jdbc.update("DELETE FROM dsvp_request_snapshots WHERE source_ref = ?", SOURCE_REF);
        jdbc.update("DELETE FROM learning_records WHERE user_id = ?", SPRING_USER_ID);
        jdbc.update("DELETE FROM animation_observations WHERE user_id = ?", SPRING_USER_ID);
        jdbc.update("DELETE FROM animation_records WHERE user_id = ?", SPRING_USER_ID);
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", SPRING_USER_ID);
        jdbc.update("DELETE FROM users WHERE id = ?", SPRING_USER_ID);
        jdbc.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            SPRING_USER_ID,
            EMAIL,
            "hash"
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'STUDENT')", SPRING_USER_ID);
    }

    @Test
    void acceptsTheFrontendNodeTokenForDsvpEvidenceAndMapsItByEmailToTheSpringUser() throws Exception {
        String token = nodeToken(NODE_USER_ID, EMAIL);
        MvcResult simulation = mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"stack","operation":"push","params":{"value":3,"capacity":8},"initial_state":{"data":[1,2],"metadata":{"capacity":8}},"source_ref":"node-token-bridge","context":{"chapter_id":"03-stack-queue","source_type":"API","source_ref":"node-token-bridge"}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidencePersisted").value(true))
            .andExpect(jsonPath("$.recordId").isNotEmpty())
            .andExpect(jsonPath("$.animationRecordId").isNotEmpty())
            .andExpect(jsonPath("$.resolvedChapterId").value("03-stack-queue"))
            .andReturn();

        JsonNode payload = objectMapper.readTree(simulation.getResponse().getContentAsString());
        String recordId = payload.path("recordId").asText();

        mockMvc.perform(post("/api/v1/animations/" + recordId + "/observations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"observation\":\"The stack top is 3.\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordId").value(recordId));

        mockMvc.perform(get("/api/v1/learning/progress")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.chapters[?(@.chapterId == '03-stack-queue')].animationCount", hasItem(1)))
            .andExpect(jsonPath("$.chapters[?(@.chapterId == '03-stack-queue')].eventCount", hasItem(2)));

        mockMvc.perform(post("/api/v1/learning/events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"RESOURCE_VIEW\",\"chapterId\":\"03-stack-queue\",\"referenceId\":\"node-token-bridge\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.eventType").value("RESOURCE_VIEW"));

        assertThat(jdbc.queryForObject(
            "SELECT user_id FROM learning_records WHERE reference_id = ? ORDER BY id DESC LIMIT 1",
            Long.class,
            "node-token-bridge"
        )).isEqualTo(SPRING_USER_ID);
    }

    @Test
    void doesNotUseANodeCompatibilityTokenOutsideTheLearningAndAnimationBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + nodeToken(NODE_USER_ID, EMAIL)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void rejectsAnInvalidNodeCompatibilitySignatureBeforeAnyEvidenceIsCreated() throws Exception {
        mockMvc.perform(post("/api/v1/learning/events")
                .header("Authorization", "Bearer " + nodeToken(
                    NODE_USER_ID,
                    EMAIL,
                    "different-node-compatibility-secret-with-at-least-32-characters"
                ))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"RESOURCE_VIEW\",\"chapterId\":\"03-stack-queue\",\"referenceId\":\"invalid-node-token\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM learning_records WHERE reference_id = ?",
            Integer.class,
            "invalid-node-token"
        )).isZero();
    }

    @Test
    void keepsStandardSpringJwtAuthenticationAvailableOutsideTheCompatibilityBoundary() throws Exception {
        String springToken = springTokens.issue(SPRING_USER_ID, EMAIL, Set.of("STUDENT"));

        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + springToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(SPRING_USER_ID))
            .andExpect(jsonPath("$.email").value(EMAIL));
    }

    private String nodeToken(long userId, String email) throws Exception {
        return nodeToken(userId, email, TEST_SECRET);
    }

    private String nodeToken(long userId, String email, String secret) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode("{\"userId\":" + userId + ",\"email\":\"" + email
            + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}");
        String unsigned = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
