package com.feng.dsagent.modelconfig;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.security.JwtTokenService;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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
@Import(ConfiguredModelConfigApiIntegrationTest.TestMasterKeyConfiguration.class)
class ConfiguredModelConfigApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @BeforeEach
    void clearConfigurationAndUsers() {
        jdbc.update("DELETE FROM model_configurations");
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void administratorCanSaveOnlyADetachedConfigurationViewAndMustReplaceTheKeyForANewTarget() throws Exception {
        long adminId = seedUser("model-config-save@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        String credentialInput = UUID.randomUUID().toString();
        String token = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configuration("custom", "https://1.1.1.1/v1", "model-a", credentialInput)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.provider").value("custom"))
            .andExpect(jsonPath("$.baseUrl").value("https://1.1.1.1/v1"))
            .andExpect(jsonPath("$.model").value("model-a"))
            .andExpect(jsonPath("$.apiKeyConfigured").value(true))
            .andExpect(jsonPath("$.apiKey").doesNotExist())
            .andExpect(content().string(not(containsString(credentialInput))));

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://8.8.8.8/v1","model":"model-b"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MODEL_CONFIG_API_KEY_REQUIRED"));

        mockMvc.perform(get("/api/v1/admin/model-config").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("PERSISTED_CONFIGURATION_DISABLED"))
            .andExpect(jsonPath("$.configuration.provider").value("custom"))
            .andExpect(jsonPath("$.configuration.baseUrl").value("https://1.1.1.1/v1"))
            .andExpect(jsonPath("$.configuration.apiKeyConfigured").value(true))
            .andExpect(jsonPath("$.configuration.apiKey").doesNotExist())
            .andExpect(content().string(not(containsString(credentialInput))));
    }

    @Test
    void unconfiguredModelSettingsAreUnavailableInBothAdminCapabilityEndpoints() throws Exception {
        long adminId = seedUser("model-config-unconfigured@example.com", "ACTIVE", "STUDENT", "ADMIN");
        String token = bearer(adminId, "STUDENT", "ADMIN");

        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modules.modelSettings.available").value(false))
            .andExpect(jsonPath("$.modules.modelSettings.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.modules.modelSettings.reason").value("NOT_CONFIGURED"));

        mockMvc.perform(get("/api/v1/admin/model-config").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("NOT_CONFIGURED"))
            .andExpect(jsonPath("$.configuration").doesNotExist());
    }

    @Test
    void disabledPersistedConfigurationIsUnavailableInTheAdminCapabilities() throws Exception {
        long adminId = seedUser("model-config-disabled-capability@example.com", "ACTIVE", "STUDENT", "ADMIN");
        String token = bearer(adminId, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://1.1.1.1/v1","model":"model-a","apiKey":"opaque-key",
                     "enabled":false,"dailyTokenQuota":4096}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modules.modelSettings.available").value(false))
            .andExpect(jsonPath("$.modules.modelSettings.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.modules.modelSettings.reason").value("PERSISTED_CONFIGURATION_DISABLED"));

        mockMvc.perform(get("/api/v1/admin/model-config").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("PERSISTED_CONFIGURATION_DISABLED"))
            .andExpect(jsonPath("$.configuration.enabled").value(false))
            .andExpect(jsonPath("$.configuration.apiKey").doesNotExist());
    }

    @Test
    void persistedConfigurationWithoutAQuotaIsUnavailableInTheAdminCapabilities() throws Exception {
        long adminId = seedUser("model-config-quota-capability@example.com", "ACTIVE", "STUDENT", "ADMIN");
        String token = bearer(adminId, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://1.1.1.1/v1","model":"model-a","apiKey":"opaque-key",
                     "enabled":true,"dailyTokenQuota":4096}
                    """))
            .andExpect(status().isOk());
        jdbc.update("UPDATE model_configurations SET daily_token_quota = 0 WHERE id = ?", 1L);

        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modules.modelSettings.available").value(false))
            .andExpect(jsonPath("$.modules.modelSettings.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.modules.modelSettings.reason").value("PERSISTED_QUOTA_NOT_CONFIGURED"));
    }

    @Test
    void persistedCredentialUnsafeForHttpHeadersIsUnavailableInTheAdminCapabilities() throws Exception {
        long adminId = seedUser("model-config-unsafe-key-capability@example.com", "ACTIVE", "STUDENT", "ADMIN");
        String token = bearer(adminId, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://1.1.1.1/v1","model":"model-a","apiKey":"opaque\\r\\nkey",
                     "enabled":true,"dailyTokenQuota":4096}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("opaque"))));

        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modules.modelSettings.available").value(false))
            .andExpect(jsonPath("$.modules.modelSettings.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.modules.modelSettings.reason").value("MODEL_CONFIG_UNAVAILABLE"))
            .andExpect(content().string(not(containsString("opaque"))));
    }

    @Test
    void changingThePersistedProviderInvalidatesTheEncryptedCredential() throws Exception {
        long adminId = seedUser("model-config-provider-binding@example.com", "ACTIVE", "STUDENT", "ADMIN");
        String token = bearer(adminId, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configuration("custom", "https://1.1.1.1/v1", "model-a", UUID.randomUUID().toString())))
            .andExpect(status().isOk());
        jdbc.update(
            "UPDATE model_configurations SET provider = ?, enabled = TRUE, daily_token_quota = ? WHERE id = ?",
            "other-provider",
            4_096L,
            1L
        );

        mockMvc.perform(get("/api/v1/admin/model-config").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("MODEL_CONFIG_UNAVAILABLE"));

        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modules.modelSettings.available").value(false))
            .andExpect(jsonPath("$.modules.modelSettings.reason").value("MODEL_CONFIG_UNAVAILABLE"));

        mockMvc.perform(post("/api/v1/admin/model-config/test").header("Authorization", token))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("MODEL_CONFIG_UNAVAILABLE"));
    }

    @Test
    void changingThePersistedOriginInvalidatesTheEncryptedCredential() throws Exception {
        long adminId = seedUser("model-config-origin-binding@example.com", "ACTIVE", "STUDENT", "ADMIN");
        String token = bearer(adminId, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configuration("custom", "https://1.1.1.1/v1", "model-a", UUID.randomUUID().toString())))
            .andExpect(status().isOk());
        jdbc.update("UPDATE model_configurations SET base_url = ? WHERE id = ?", "https://8.8.8.8/v1", 1L);

        mockMvc.perform(get("/api/v1/admin/model-config").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("MODEL_CONFIG_UNAVAILABLE"));

        mockMvc.perform(post("/api/v1/admin/model-config/test").header("Authorization", token))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("MODEL_CONFIG_UNAVAILABLE"));
    }

    @Test
    void unsafeBaseUrlsAreRejectedWithoutRetainingOrDisclosingAKey() throws Exception {
        long adminId = seedUser("model-config-url@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://127.0.0.1/metadata","model":"model-a"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MODEL_CONFIG_URL_UNSAFE"))
            .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test
    void configuredConnectionTestsCrossAnExplicitProviderBoundaryWithoutReturningTheCredential() throws Exception {
        long adminId = seedUser("model-config-test@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        String credentialInput = UUID.randomUUID().toString();
        String token = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configuration("custom", "https://1.1.1.1/v1", "model-a", credentialInput)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/model-config/test").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.code").value("CONNECTION_OK"))
            .andExpect(jsonPath("$.apiKey").doesNotExist())
            .andExpect(content().string(not(containsString(credentialInput))));
    }

    @Test
    void administratorCanPersistRuntimeControlsConnectionEvidenceAndAuditableWritesWithoutLeakingTheKey() throws Exception {
        long adminId = seedUser("model-config-controls@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        String credentialInput = UUID.randomUUID().toString();
        String requestId = "model-config-controls-request";
        String token = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://1.1.1.1/v1","model":"model-a","apiKey":"%s",
                     "temperature":0.35,"maxOutputTokens":640,"requestTimeoutMs":12000,"retryCount":2,
                     "dailyTokenQuota":4096,"enabled":true}
                    """.formatted(credentialInput)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.temperature").value(0.35))
            .andExpect(jsonPath("$.maxOutputTokens").value(640))
            .andExpect(jsonPath("$.requestTimeoutMs").value(12000))
            .andExpect(jsonPath("$.retryCount").value(2))
            .andExpect(jsonPath("$.dailyTokenQuota").value(4096))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.lastConnectionTestStatus").doesNotExist())
            .andExpect(content().string(not(containsString(credentialInput))));

        mockMvc.perform(get("/api/v1/admin/audit-events")
                .header("Authorization", token)
                .param("actorUserId", Long.toString(adminId))
                .param("action", "MODEL_CONFIG_UPDATED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].actorUserId").value(adminId))
            .andExpect(jsonPath("$.items[0].targetType").value("MODEL_CONFIG"))
            .andExpect(jsonPath("$.items[0].targetId").value("1"))
            .andExpect(jsonPath("$.items[0].result").value("SUCCESS"))
            .andExpect(jsonPath("$.items[0].requestId").value(requestId))
            .andExpect(content().string(not(containsString(credentialInput))));

        mockMvc.perform(post("/api/v1/admin/model-config/test")
                .header("Authorization", token)
                .header("X-Request-Id", requestId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.code").value("CONNECTION_OK"))
            .andExpect(content().string(not(containsString(credentialInput))));

        mockMvc.perform(get("/api/v1/admin/model-config").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configuration.temperature").value(0.35))
            .andExpect(jsonPath("$.configuration.maxOutputTokens").value(640))
            .andExpect(jsonPath("$.configuration.requestTimeoutMs").value(12000))
            .andExpect(jsonPath("$.configuration.retryCount").value(2))
            .andExpect(jsonPath("$.configuration.dailyTokenQuota").value(4096))
            .andExpect(jsonPath("$.configuration.enabled").value(true))
            .andExpect(jsonPath("$.configuration.lastConnectionTestStatus").value("CONNECTION_OK"))
            .andExpect(jsonPath("$.configuration.lastConnectionTestedAt").exists())
            .andExpect(content().string(not(containsString(credentialInput))));

        mockMvc.perform(get("/api/v1/admin/audit-events")
                .header("Authorization", token)
                .param("actorUserId", Long.toString(adminId))
                .param("action", "MODEL_CONFIG_CONNECTION_TESTED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].actorUserId").value(adminId))
            .andExpect(jsonPath("$.items[0].targetType").value("MODEL_CONFIG"))
            .andExpect(jsonPath("$.items[0].targetId").value("1"))
            .andExpect(jsonPath("$.items[0].result").value("SUCCESS"))
            .andExpect(jsonPath("$.items[0].requestId").value(requestId))
            .andExpect(jsonPath("$.items[0].afterSummary", containsString("connectionTestElapsedMs=")))
            .andExpect(jsonPath("$.items[0].afterSummary", containsString("credentialRedacted=true")))
            .andExpect(content().string(not(containsString(credentialInput))));
    }

    @Test
    void changingTheModelInvalidatesThePreviousConnectionTestWithoutRequiringAnotherKey() throws Exception {
        long adminId = seedUser("model-config-model-change@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        String credentialInput = UUID.randomUUID().toString();
        String token = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configuration("custom", "https://1.1.1.1/v1", "model-a", credentialInput)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/model-config/test").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("CONNECTION_OK"));

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://1.1.1.1/v1","model":"model-b"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("model-b"))
            .andExpect(jsonPath("$.apiKeyConfigured").value(true))
            .andExpect(jsonPath("$.lastConnectionTestStatus").doesNotExist())
            .andExpect(jsonPath("$.lastConnectionTestedAt").doesNotExist())
            .andExpect(content().string(not(containsString(credentialInput))));
    }

    @Test
    void enablingAConfigurationRequiresAnExplicitPositiveDailyQuota() throws Exception {
        long adminId = seedUser("model-config-quota@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        String credentialInput = UUID.randomUUID().toString();

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider":"custom","baseUrl":"https://1.1.1.1/v1","model":"model-a","apiKey":"%s",
                     "enabled":true,"dailyTokenQuota":0}
                    """.formatted(credentialInput)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MODEL_CONFIG_QUOTA_REQUIRED"))
            .andExpect(content().string(not(containsString(credentialInput))));
    }

    @Test
    void replacingTheKeyInvalidatesThePreviousConnectionTestForTheSameTarget() throws Exception {
        long adminId = seedUser("model-config-key-refresh@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        String originalCredential = UUID.randomUUID().toString();
        String replacementCredential = UUID.randomUUID().toString();
        String token = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configuration("custom", "https://1.1.1.1/v1", "model-a", originalCredential)))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/model-config/test").header("Authorization", token))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/model-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configuration("custom", "https://1.1.1.1/v1", "model-a", replacementCredential)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lastConnectionTestStatus").doesNotExist())
            .andExpect(jsonPath("$.lastConnectionTestedAt").doesNotExist())
            .andExpect(content().string(not(containsString(originalCredential))))
            .andExpect(content().string(not(containsString(replacementCredential))));
    }

    private String configuration(String provider, String baseUrl, String model, String credentialInput) {
        return """
            {"provider":"%s","baseUrl":"%s","model":"%s","apiKey":"%s"}
            """.formatted(provider, baseUrl, model, credentialInput);
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
    static class TestMasterKeyConfiguration {

        @Bean
        @Primary
        ModelConfigMasterKeySource testMasterKeySource() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            SecretKey key = new SecretKeySpec(bytes, "AES");
            return () -> Optional.of(key);
        }

        @Bean
        @Primary
        ModelConfigConnectionTester testConnectionTester() {
            return connection -> new ModelConfigConnectionResult(true, "CONNECTION_OK");
        }
    }
}
