package com.feng.dsagent.auth;

import java.util.Set;

public record UserAccount(long id, String email, String username, String passwordHash, Set<String> roles) {

    public UserAccount {
        roles = Set.copyOf(roles);
    }

    public UserView toView() {
        return new UserView(id, email, username, roles);
    }
}
