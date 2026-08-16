package com.feng.dsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class AdminAccountProvisionerIntegrationTest {

    private static final String EMAIL = "bootstrap-admin-test@example.com";
    private static final String RECONCILE_EMAIL = "bootstrap-admin-reconcile@example.com";
    private static final String TARGET_EMAIL = "bootstrap-admin-target@example.com";
    private static final String OTHER_EMAIL = "bootstrap-admin-other@example.com";

    @Autowired
    private AdminAccountProvisioner provisioner;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void removeBootstrapTestAccount() {
        for (String email : List.of(EMAIL, RECONCILE_EMAIL, TARGET_EMAIL, OTHER_EMAIL)) {
            jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
            jdbc.update("DELETE FROM users WHERE email = ?", email);
        }
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

    @Test
    void reconcileEnabledRepairsOneExistingAccountAtomically() {
        long existingId = seedUser(
            RECONCILE_EMAIL,
            null,
            "DISABLED",
            passwords.encode("old-password"),
            Set.of("STUDENT")
        );

        UserAccount repaired = provisioner.provisionAdministrator(
            RECONCILE_EMAIL,
            "ACha_",
            "correct-horse",
            true
        );

        assertThat(repaired.id()).isEqualTo(existingId);
        assertThat(repaired.email()).isEqualTo(RECONCILE_EMAIL);
        assertThat(repaired.username()).isEqualTo("ACha_");
        assertThat(repaired.roles()).containsExactlyInAnyOrder("STUDENT", "TEACHER", "ADMIN");
        assertThat(passwords.matches("correct-horse", repaired.passwordHash())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, existingId))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT disabled_reason FROM users WHERE id = ?", String.class, existingId))
            .isNull();
        assertThat(jdbc.queryForList(
            "SELECT role FROM user_roles WHERE user_id = ? ORDER BY role",
            String.class,
            existingId
        )).containsExactly("ADMIN", "STUDENT", "TEACHER");
    }

    @Test
    void reconcileDisabledKeepsExistingAccountConflictsRejected() {
        long existingId = seedUser(
            RECONCILE_EMAIL,
            null,
            "ACTIVE",
            passwords.encode("old-password"),
            Set.of("STUDENT", "TEACHER", "ADMIN")
        );

        assertThatThrownBy(() -> provisioner.provisionAdministrator(
            RECONCILE_EMAIL,
            "ACha_",
            "correct-horse",
            false
        )).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.code()).isEqualTo("AUTH_EMAIL_REGISTERED");
        });

        assertThat(jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, existingId)).isNull();
        assertThat(passwords.matches(
            "old-password",
            jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, existingId)
        )).isTrue();
    }

    @Test
    void reconcileRejectsEmailAndUsernameThatPointToDifferentAccounts() {
        long emailTargetId = seedUser(
            TARGET_EMAIL,
            "Other_1",
            "ACTIVE",
            passwords.encode("email-target-password"),
            Set.of("STUDENT")
        );
        long usernameTargetId = seedUser(
            OTHER_EMAIL,
            "ACha_",
            "ACTIVE",
            passwords.encode("username-target-password"),
            Set.of("STUDENT")
        );

        assertThatThrownBy(() -> provisioner.provisionAdministrator(
            TARGET_EMAIL,
            "ACha_",
            "correct-horse",
            true
        )).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.code()).isEqualTo("AUTH_ADMIN_PROVISION_TARGET_MISMATCH");
        });

        assertThat(passwords.matches(
            "email-target-password",
            jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, emailTargetId)
        )).isTrue();
        assertThat(passwords.matches(
            "username-target-password",
            jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, usernameTargetId)
        )).isTrue();
    }

    private long seedUser(String email, String username, String status, String passwordHash, Set<String> roles) {
        jdbc.update(
            "INSERT INTO users (email, username, username_normalized, password_hash, status) VALUES (?, ?, ?, ?, ?)",
            email,
            username,
            username == null ? null : UsernamePolicy.lookupKey(username),
            passwordHash,
            status
        );
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, role);
        }
        return userId;
    }
}
