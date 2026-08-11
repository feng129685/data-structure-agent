package com.feng.dsagent.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record SecurityProperties(
    String jwtSecret,
    Duration tokenTtl,
    String issuer,
    String cookieName,
    boolean cookieSecure,
    String corsAllowedOrigins,
    String bootstrapAdminEmail,
    String teacherEmails,
    boolean nodeCompatEnabled,
    String nodeCompatJwtSecret
) {
}
