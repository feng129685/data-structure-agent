package com.feng.dsagent.modelconfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.security.JwtTokenService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ModelConfigApiIntegrationTest.MissingMasterKeyConfiguration.class)
class ModelConfigApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @BeforeEach
    void clearUsers() {
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void modelConfigurationCapabilityUsesTheCurrentDatabaseAdminRoleAndFailsClosedWithoutAMasterKey() throws Exception {
        long adminId = seedUser("model-config-admin@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        long studentId = seedUser("model-config-student@example.com", "ACTIVE", "STUDENT");

        mockMvc.perform(get("/api/v1/admin/model-config"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/admin/model-config").header("Authorization", bearer(studentId, "ADMIN")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", bearer(studentId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://1.1.1.1/v1","model":"model-a"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/model-config/test").header("Authorization", bearer(studentId, "ADMIN")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/model-config")
                .header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("MASTER_KEY_UNAVAILABLE"))
            .andExpect(jsonPath("$.configuration").doesNotExist())
            .andExpect(jsonPath("$.apiKey").doesNotExist());

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://1.1.1.1/v1","model":"model-a"}
                    """))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("MODEL_CONFIG_UNAVAILABLE"));

        mockMvc.perform(post("/api/v1/admin/model-config/test")
                .header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("MODEL_CONFIG_UNAVAILABLE"));
    }

    private long seedUser(String email, String status, String... roles) {
        jdbc.update(
            "INSERT INTO users (email, password_hash, status) VALUES (?, ?, ?)",
            email,
            "test-password-hash",
            status
        );
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    private String bearer(long userId, String... roles) {
        return "Bearer " + tokens.issue(userId, "token-" + userId + "@example.com", Set.of(roles));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MissingMasterKeyConfiguration {

        @Bean
        @Primary
        ModelConfigMasterKeySource missingMasterKeySource() {
            return Optional::empty;
        }
    }
}
