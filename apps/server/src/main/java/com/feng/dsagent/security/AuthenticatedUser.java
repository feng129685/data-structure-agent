package com.feng.dsagent.security;

import java.util.Set;

public record AuthenticatedUser(long userId, String email, Set<String> roles) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
