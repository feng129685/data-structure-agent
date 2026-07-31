package com.feng.dsagent.auth;

import java.util.Set;

public record UserView(long id, String email, Set<String> roles) {

    public UserView {
        roles = Set.copyOf(roles);
    }
}
