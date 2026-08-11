package com.feng.dsagent.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.security.node-compat-enabled=false",
    "app.security.node-compat-jwt-secret="
})
@AutoConfigureMockMvc
class NodeTokenCompatibilityDisabledApiIntegrationTest {

    private static final String TEST_SECRET = "node-compat-test-secret-with-at-least-32-characters";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsNodeTokensWhenCompatibilityIsDisabledWithoutRequiringACompatibilitySecret() throws Exception {
        mockMvc.perform(get("/api/v1/learning/progress")
                .header("Authorization", "Bearer " + nodeToken(31L, "node-disabled@example.com")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
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
