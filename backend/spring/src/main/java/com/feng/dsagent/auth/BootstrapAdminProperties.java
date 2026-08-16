package com.feng.dsagent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.bootstrap-admin")
public record BootstrapAdminProperties(boolean enabled, String email, String username, String password) {
}
