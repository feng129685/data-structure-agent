package com.feng.dsagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JwtTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:30:00Z");
    private static final String SECRET = "test-secret-with-at-least-thirty-two-characters";

    @Test
    void issuesAndVerifiesSignedToken() {
        JwtTokenService service = serviceAt(NOW, Duration.ofMinutes(30));

        String token = service.issue(42L, "student@example.com", Set.of("STUDENT"));
        AuthenticatedUser user = service.verify(token);

        assertThat(user.userId()).isEqualTo(42L);
        assertThat(user.email()).isEqualTo("student@example.com");
        assertThat(user.roles()).containsExactly("STUDENT");
    }

    @Test
    void rejectsTamperedToken() {
        JwtTokenService service = serviceAt(NOW, Duration.ofMinutes(30));
        String token = service.issue(42L, "student@example.com", Set.of("STUDENT"));

        assertThatThrownBy(() -> service.verify(token.substring(0, token.length() - 1) + "x"))
            .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtTokenService issuer = serviceAt(NOW, Duration.ofMinutes(1));
        String token = issuer.issue(42L, "student@example.com", Set.of("STUDENT"));
        JwtTokenService verifier = serviceAt(NOW.plus(Duration.ofMinutes(2)), Duration.ofMinutes(1));

        assertThatThrownBy(() -> verifier.verify(token))
            .isInstanceOf(InvalidTokenException.class)
            .hasMessageContaining("expired");
    }

    private JwtTokenService serviceAt(Instant instant, Duration ttl) {
        return new JwtTokenService(
            SECRET,
            ttl,
            "ds-agent-test",
            new ObjectMapper(),
            Clock.fixed(instant, ZoneOffset.UTC)
        );
    }
}
