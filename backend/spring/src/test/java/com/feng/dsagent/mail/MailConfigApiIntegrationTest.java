package com.feng.dsagent.mail;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.security.JwtTokenService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MailConfigApiIntegrationTest.MailTestConfiguration.class)
class MailConfigApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private CapturingMailTransport transport;

    @BeforeEach
    void clearData() {
        jdbc.update("DELETE FROM mail_configurations");
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("DELETE FROM verification_codes");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        transport.reset();
    }

    @Test
    void mailConfigurationIsAdminOnlyAndDoesNotReturnASecret() throws Exception {
        long admin = seedUser("mail-admin@example.com", "ADMIN");
        long student = seedUser("mail-student@example.com", "STUDENT");

        mockMvc.perform(get("/api/v1/admin/mail-config"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/v1/admin/mail-config").header("Authorization", bearer(student, "STUDENT")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/mail-config").header("Authorization", bearer(admin, "STUDENT", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.configuration.smtpPassword").doesNotExist())
            .andExpect(jsonPath("$.configuration.smtpPasswordCiphertext").doesNotExist())
            .andExpect(jsonPath("$.configuration.smtpPasswordConfigured").value(false));
    }

    @Test
    void blankPasswordPreservesTheCiphertextAndClearRemovesIt() throws Exception {
        long admin = seedUser("mail-preserve@example.com", "ADMIN");
        String token = bearer(admin, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .header("X-Request-Id", "mail-save")
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson(true, "test-only-value", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smtpPasswordConfigured").value(true));
        String ciphertext = jdbc.queryForObject(
            "SELECT smtp_password_ciphertext FROM mail_configurations WHERE id = 1", String.class
        );
        org.assertj.core.api.Assertions.assertThat(ciphertext).isNotBlank().doesNotContain("test-only-value");

        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson(true, "", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smtpPasswordConfigured").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT smtp_password_ciphertext FROM mail_configurations WHERE id = 1", String.class
        )).isEqualTo(ciphertext);

        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson(false, "", true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smtpPasswordConfigured").value(false));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT smtp_password_ciphertext FROM mail_configurations WHERE id = 1", String.class
        )).isNull();

        mockMvc.perform(get("/api/v1/admin/audit-events?targetType=MAIL_CONFIG")
                .header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items[0].afterSummary", not(containsString("test-only-value"))))
            .andExpect(jsonPath("$.items[0].requestId").isString());
    }

    @Test
    void changingSmtpConnectionIdentityRequiresANewPassword() throws Exception {
        long admin = seedUser("mail-identity@example.com", "ADMIN");
        String token = bearer(admin, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson("smtp.example.invalid", true, "test-only-value", false)))
            .andExpect(status().isOk());
        String ciphertext = jdbc.queryForObject(
            "SELECT smtp_password_ciphertext FROM mail_configurations WHERE id = 1", String.class
        );

        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson("smtp.changed.example.invalid", true, "", false)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MAIL_SMTP_PASSWORD_REQUIRED"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap(
            "SELECT smtp_host, smtp_password_ciphertext FROM mail_configurations WHERE id = 1"
        ))
            .containsEntry("SMTP_HOST", "smtp.example.invalid")
            .containsEntry("SMTP_PASSWORD_CIPHERTEXT", ciphertext);
    }

    @Test
    void unsavedConnectionAndTestMailUseDraftAndOnlyReachTheCurrentAdministrator() throws Exception {
        long admin = seedUser("mail-draft@example.com", "ADMIN");
        String token = bearer(admin, "STUDENT", "ADMIN");
        String config = configJson(true, "test-only-value", false);

        mockMvc.perform(post("/api/v1/admin/mail-config/test-connection")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(config))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.code").value("CONNECTION_OK"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM mail_configurations", Integer.class
        )).isZero();

        mockMvc.perform(post("/api/v1/admin/mail-config/test-email")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"config\":" + config + ",\"recipient\":\"mail-draft@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sent").value(true))
            .andExpect(jsonPath("$.code").value("TEST_EMAIL_SENT"));
        org.assertj.core.api.Assertions.assertThat(transport.lastHtml()).contains("123456");

        mockMvc.perform(post("/api/v1/admin/mail-config/test-email")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"config\":" + config + ",\"recipient\":\"other@example.com\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MAIL_TEST_RECIPIENT_FORBIDDEN"));
    }

    @Test
    void templateRejectsUnknownVariablesAndScripts() throws Exception {
        long admin = seedUser("mail-template@example.com", "ADMIN");
        String token = bearer(admin, "STUDENT", "ADMIN");
        String unknown = configJson(false, "", false).replace("{{code}}", "{{unknown}}");
        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(unknown))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MAIL_TEMPLATE_VARIABLE_INVALID"));

        String script = configJson(false, "", false).replace("<main>", "<script>alert(1)</script><main>");
        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(script))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MAIL_TEMPLATE_UNSAFE_MARKUP"));
    }

    @Test
    void savesOnlyThePublicSmtpHostAndRejectsPrivateNetworkLiterals() throws Exception {
        long admin = seedUser("mail-host@example.com", "ADMIN");
        String token = bearer(admin, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson("mail.structify.cn", true, "test-only-value", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smtpHost").value("mail.structify.cn"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT smtp_host FROM mail_configurations WHERE id = 1", String.class
        )).isEqualTo("mail.structify.cn");

        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson("10.0.0.8", true, "test-only-value", false)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MAIL_SMTP_HOST_FORBIDDEN"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT smtp_host FROM mail_configurations WHERE id = 1", String.class
        )).isEqualTo("mail.structify.cn");
    }

    @Test
    void connectionTestsPersistSuccessAndFailureWithoutRecordingTheSmtpPassword() throws Exception {
        long admin = seedUser("mail-connection-state@example.com", "ADMIN");
        String token = bearer(admin, "STUDENT", "ADMIN");

        mockMvc.perform(put("/api/v1/admin/mail-config")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson(true, "test-only-value", false)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/mail-config/test-connection")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson(true, "", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.code").value("CONNECTION_OK"))
            .andExpect(jsonPath("$.smtpPassword").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/mail-config")
                .header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configuration.lastConnectionTestStatus").value("CONNECTION_OK"))
            .andExpect(jsonPath("$.configuration.lastConnectionTestedAt").isNotEmpty());

        transport.failConnectionTests();
        mockMvc.perform(post("/api/v1/admin/mail-config/test-connection")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configJson(true, "", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.code").value("CONNECTION_FAILED"));
        mockMvc.perform(get("/api/v1/admin/mail-config")
                .header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configuration.lastConnectionTestStatus").value("CONNECTION_FAILED"))
            .andExpect(jsonPath("$.configuration.lastConnectionTestedAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/admin/audit-events?targetType=MAIL_CONFIG")
                .header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].afterSummary", not(containsString("test-only-value"))));
    }

    private long seedUser(String email, String role) {
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-hash')", email);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'STUDENT')", id);
        if (!"STUDENT".equals(role)) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    private String bearer(long userId, String... roles) {
        return "Bearer " + tokens.issue(userId, "token-" + userId + "@example.com", Set.of(roles));
    }

    private String configJson(boolean enabled, String password, boolean clearPassword) {
        return configJson("smtp.example.invalid", enabled, password, clearPassword);
    }

    private String configJson(String smtpHost, boolean enabled, String password, boolean clearPassword) {
        return """
            {
              "siteName":"Structify",
              "enabled":%s,
              "smtpHost":"%s",
              "smtpPort":465,
              "securityMode":"SSL",
              "smtpUsername":"mailer@example.invalid",
              "smtpPassword":"%s",
              "clearSmtpPassword":%s,
              "fromEmail":"mail-draft@example.com",
              "fromName":"Structify",
              "connectionTimeoutSeconds":5,
              "verificationTtlMinutes":10,
              "resendIntervalSeconds":60,
              "sessionTtlDays":30,
              "verificationSubject":"[{{site_name}}] verification code",
              "verificationTemplateHtml":"<main><h1>{{code}}</h1><p>{{expires_minutes}}</p></main>"
            }
            """.formatted(enabled, smtpHost, password, clearPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailTestConfiguration {
        @Bean
        @Primary
        MailConfigMasterKeySource mailConfigMasterKeySource() {
            SecretKey key = new SecretKeySpec(new byte[32], "AES");
            return () -> Optional.of(key);
        }

        @Bean
        @Primary
        CapturingMailTransport capturingMailTransport() {
            return new CapturingMailTransport();
        }
    }

    static class CapturingMailTransport implements MailTransport {
        private final List<String> subjects = new ArrayList<>();
        private final List<String> html = new ArrayList<>();
        private boolean connectionTestsFail;

        @Override
        public void testConnection(MailConnection connection, String password) {
            if (connectionTestsFail) {
                throw new IllegalStateException("test-only connection failure");
            }
            subjects.add("connection");
        }

        @Override
        public void send(MailConnection connection, String password, String recipient, String subject, String body) {
            subjects.add(subject);
            html.add(body);
        }

        String lastHtml() {
            return html.isEmpty() ? "" : html.get(html.size() - 1);
        }

        void failConnectionTests() {
            connectionTestsFail = true;
        }

        void reset() {
            subjects.clear();
            html.clear();
            connectionTestsFail = false;
        }
    }
}
