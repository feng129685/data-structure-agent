package com.feng.dsagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.security.bootstrap-admin-email=node-compat-missing-admin@example.com",
    "app.security.teacher-emails=node-compat-missing-teacher@example.com"
})
@AutoConfigureMockMvc
class NodeCompatibilityPrivilegeEscalationApiIntegrationTest {

    private static final String ADMIN_EMAIL = "node-compat-missing-admin@example.com";
    private static final String TEACHER_EMAIL = "node-compat-missing-teacher@example.com";
    private static final String TEAM_ONLY_RESOURCE_ID = "node-compat-team-only-resource";
    private static final String TEST_SECRET = "node-compat-test-secret-with-at-least-32-characters";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareTeamOnlyEvidenceSource() {
        clearBridgeUsers();
        jdbc.update("DELETE FROM resources WHERE id = ?", TEAM_ONLY_RESOURCE_ID);
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, source_name,
                version_label, review_status, license_scope
            ) VALUES (?, '02-linear-list', 'ANIMATION', 'Node bridge team-only animation', '',
                'node-bridge.json', '1.0', 'PUBLISHED', 'TEAM_ONLY')
            """,
            TEAM_ONLY_RESOURCE_ID
        );
    }

    @AfterEach
    void cleanUp() {
        clearBridgeUsers();
        jdbc.update("DELETE FROM resources WHERE id = ?", TEAM_ONLY_RESOURCE_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_EMAIL, TEACHER_EMAIL})
    void configuredPrivilegedEmailWithoutASpringAccountCannotAccessTeamOnlyEvidence(String email) throws Exception {
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?",
            Integer.class,
            email
        )).isZero();

        mockMvc.perform(post("/api/v1/animations/simulate")
                .header("Authorization", "Bearer " + nodeToken(91301L, email))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0","structure":"stack","operation":"peek","params":{},"initial_state":{"data":[1]},"source_ref":"node-compat-team-only-resource","context":{"source_type":"API","source_ref":"node-compat-team-only-resource"}}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("DSVP_SOURCE_FORBIDDEN"));
    }

    private void clearBridgeUsers() {
        for (Long userId : jdbc.queryForList(
            "SELECT id FROM users WHERE email IN (?, ?)",
            Long.class,
            ADMIN_EMAIL,
            TEACHER_EMAIL
        )) {
            jdbc.update("DELETE FROM animation_observations WHERE user_id = ?", userId);
            jdbc.update(
                "DELETE FROM dsvp_request_snapshots WHERE animation_record_id IN "
                    + "(SELECT id FROM animation_records WHERE user_id = ?)",
                userId
            );
            jdbc.update("DELETE FROM learning_records WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM animation_records WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    private String nodeToken(long userId, String email) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode("{\"userId\":" + userId + ",\"email\":\"" + email
            + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}");
        String unsigned = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
