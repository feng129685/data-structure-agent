package com.feng.dsagent.auth;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class RolePolicy {

    private final String adminEmail;
    private final Set<String> teacherEmails;

    public RolePolicy(String adminEmail, String teacherEmails) {
        this.adminEmail = normalize(adminEmail);
        this.teacherEmails = parseEmails(teacherEmails);
    }

    public Set<String> rolesFor(String email) {
        String normalized = normalize(email);
        Set<String> roles = new LinkedHashSet<>();
        roles.add("STUDENT");
        if (teacherEmails.contains(normalized) || (!adminEmail.isBlank() && adminEmail.equals(normalized))) {
            roles.add("TEACHER");
        }
        if (!adminEmail.isBlank() && adminEmail.equals(normalized)) {
            roles.add("ADMIN");
        }
        return Set.copyOf(roles);
    }

    private Set<String> parseEmails(String value) {
        Set<String> emails = new LinkedHashSet<>();
        if (value != null) {
            for (String email : value.split(",")) {
                String normalized = normalize(email);
                if (!normalized.isBlank()) {
                    emails.add(normalized);
                }
            }
        }
        return Set.copyOf(emails);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
