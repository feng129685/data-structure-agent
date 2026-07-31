package com.feng.dsagent.knowledge;

import com.feng.dsagent.security.AuthenticatedUser;
import java.util.Locale;

public enum KnowledgeAudience {
    GUEST,
    STUDENT,
    TEAM;

    public static KnowledgeAudience from(AuthenticatedUser user) {
        if (user == null) {
            return GUEST;
        }
        return user.hasRole("TEACHER") || user.hasRole("ADMIN") ? TEAM : STUDENT;
    }

    boolean allows(String licenseScope) {
        String scope = licenseScope == null ? "" : licenseScope.trim().toUpperCase(Locale.ROOT);
        return switch (scope) {
            case "PUBLIC" -> true;
            case "CLASSROOM_ONLY" -> this == STUDENT || this == TEAM;
            case "TEAM_ONLY" -> this == TEAM;
            default -> false;
        };
    }
}
