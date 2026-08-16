package com.feng.dsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.JwtTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

class AuthServiceTest {

    private final FakeUserRepository users = new FakeUserRepository();
    private final FakeVerificationCodeManager codes = new FakeVerificationCodeManager();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final JwtTokenService tokens = new JwtTokenService(
        "test-secret-with-at-least-thirty-two-characters",
        Duration.ofHours(2),
        "ds-agent-test",
        new ObjectMapper(),
        Clock.fixed(Instant.parse("2026-07-20T04:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void registersVerifiedUserAsStudent() {
        AuthService service = service(new RolePolicy("", ""));
        codes.allow("student@example.com", "register", "123456");

        AuthSession session = service.register("student@example.com", "123456", "correct-horse");

        assertThat(session.user().roles()).containsExactly("STUDENT");
        assertThat(passwords.matches("correct-horse", users.findByEmail("student@example.com").orElseThrow().passwordHash()))
            .isTrue();
        assertThat(tokens.verify(session.token()).email()).isEqualTo("student@example.com");
    }

    @Test
    void assignsConfiguredTeacherAndAdminRoles() {
        AuthService service = service(new RolePolicy("admin@example.com", "teacher@example.com,admin@example.com"));
        codes.allow("admin@example.com", "register", "654321");

        AuthSession session = service.register("ADMIN@example.com", "654321", "correct-horse");

        assertThat(session.user().roles()).containsExactlyInAnyOrder("STUDENT", "TEACHER", "ADMIN");
    }

    @Test
    void rejectsUnknownEmailAndWrongPasswordWithSameError() {
        AuthService service = service(new RolePolicy("", ""));
        codes.allow("student@example.com", "register", "123456");
        service.register("student@example.com", "123456", "correct-horse");

        assertInvalidCredentials(() -> service.login("student@example.com", "wrong-password"));
        assertInvalidCredentials(() -> service.login("missing@example.com", "wrong-password"));
    }

    @Test
    void resetsPasswordAfterConsumingAResetCode() {
        AuthService service = service(new RolePolicy("", ""));
        codes.allow("student@example.com", "register", "123456");
        service.register("student@example.com", "123456", "old-password");
        codes.allow("student@example.com", "reset", "654321");

        AuthSession session = service.resetPassword("student@example.com", "654321", "new-password");

        assertThat(session.user().email()).isEqualTo("student@example.com");
        assertInvalidCredentials(() -> service.login("student@example.com", "old-password"));
        assertThat(service.login("student@example.com", "new-password").user().email())
            .isEqualTo("student@example.com");
    }

    @Test
    void logsInWithAUsernameWhileKeepingEmailLoginCompatible() {
        AuthService service = service(new RolePolicy("", ""));
        codes.allow("username@example.com", "register", "123456");

        AuthSession registration = service.register("username@example.com", "ACha_", "123456", "correct-horse");

        assertThat(registration.user().username()).isEqualTo("ACha_");
        assertThat(service.login("acha_", "correct-horse").user().email()).isEqualTo("username@example.com");
        assertThat(service.login("username@example.com", "correct-horse").user().username()).isEqualTo("ACha_");
    }

    @Test
    void rejectsUsernameConflictsCaseInsensitively() {
        AuthService service = service(new RolePolicy("", ""));
        codes.allow("first@example.com", "register", "123456");
        service.register("first@example.com", "ACha_", "123456", "correct-horse");
        codes.allow("second@example.com", "register", "654321");

        assertThatThrownBy(() -> service.register("second@example.com", "acha_", "654321", "correct-horse"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.code()).isEqualTo("AUTH_USERNAME_REGISTERED");
            });
    }

    private void assertInvalidCredentials(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(error.code()).isEqualTo("AUTH_INVALID_CREDENTIALS");
                assertThat(error.getMessage()).isEqualTo("邮箱或密码错误");
            });
    }

    private AuthService service(RolePolicy roles) {
        return new AuthService(users, codes, passwords, tokens, roles);
    }

    private static final class FakeVerificationCodeManager implements VerificationCodeManager {
        private final Map<String, String> allowed = new LinkedHashMap<>();

        void allow(String email, String purpose, String code) {
            allowed.put(email.toLowerCase() + ":" + purpose, code);
        }

        @Override
        public VerificationCodeDelivery request(String email, String purpose) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void consume(String email, String purpose, String code) {
            String expected = allowed.remove(email.toLowerCase() + ":" + purpose);
            if (!code.equals(expected)) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_CODE_INVALID", "验证码无效或已过期");
            }
        }
    }

    private static final class FakeUserRepository implements UserRepository {
        private final Map<String, UserAccount> byEmail = new LinkedHashMap<>();
        private final Map<String, UserAccount> byUsername = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public Optional<UserAccount> findByEmail(String email) {
            return Optional.ofNullable(byEmail.get(email.toLowerCase()));
        }

        @Override
        public Optional<UserAccount> findByUsername(String normalizedUsername) {
            return Optional.ofNullable(byUsername.get(normalizedUsername));
        }

        @Override
        public Optional<UserAccount> findById(long id) {
            return byEmail.values().stream().filter(user -> user.id() == id).findFirst();
        }

        @Override
        public UserAccount create(String email, String username, String passwordHash, Set<String> roles) {
            UserAccount account = new UserAccount(nextId++, email.toLowerCase(), username, passwordHash, roles);
            byEmail.put(account.email(), account);
            if (username != null) {
                byUsername.put(UsernamePolicy.lookupKey(username), account);
            }
            return account;
        }

        @Override
        public void updatePassword(long userId, String passwordHash) {
            UserAccount current = byEmail.values().stream()
                .filter(user -> user.id() == userId)
                .findFirst()
                .orElseThrow();
            byEmail.put(current.email(), new UserAccount(
                current.id(), current.email(), current.username(), passwordHash, current.roles()
            ));
            if (current.username() != null) {
                byUsername.put(UsernamePolicy.lookupKey(current.username()), byEmail.get(current.email()));
            }
        }
    }
}
