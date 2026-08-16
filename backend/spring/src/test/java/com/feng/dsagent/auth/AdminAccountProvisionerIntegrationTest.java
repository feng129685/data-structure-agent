package com.feng.dsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class AdminAccountProvisionerIntegrationTest {

    private static final String EMAIL = "bootstrap-admin-test@example.com";

    @Autowired
    private AdminAccountProvisioner provisioner;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void removeBootstrapTestAccount() {
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)", EMAIL);
        jdbc.update("DELETE FROM users WHERE email = ?", EMAIL);
    }

    @Test
    void createsAnAllRoleAdministratorWithABcryptPasswordHash() {
        UserAccount account = provisioner.createAdministrator(EMAIL, "ACha_", "correct-horse");

        assertThat(account.email()).isEqualTo(EMAIL);
        assertThat(account.username()).isEqualTo("ACha_");
        assertThat(account.roles()).containsExactlyInAnyOrder("STUDENT", "TEACHER", "ADMIN");
        assertThat(passwords.matches("correct-horse", account.passwordHash())).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT username_normalized FROM users WHERE id = ?",
            String.class,
            account.id()
        )).isEqualTo("acha_");
    }
}
