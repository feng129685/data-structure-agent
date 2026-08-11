package com.feng.dsagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@ActiveProfiles("verification")
class VerificationProfileSmokeTest {

    @Autowired
    private Environment environment;

    @Test
    void verificationProfileUsesSafeLocalDefaults() {
        assertThat(environment.getProperty("spring.datasource.url"))
            .startsWith("jdbc:h2:mem:dsagent-verification");
        assertThat(environment.getProperty("app.auth.mail-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.knowledge.auto-publish-local", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.security.jwt-secret")).isNotBlank();
    }
}
