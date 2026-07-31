package com.feng.dsagent.auth;

import java.util.Optional;
import java.util.Set;

public interface UserRepository {
    Optional<UserAccount> findByEmail(String email);
    Optional<UserAccount> findById(long id);
    UserAccount create(String email, String passwordHash, Set<String> roles);
    void updatePassword(long userId, String passwordHash);
}
