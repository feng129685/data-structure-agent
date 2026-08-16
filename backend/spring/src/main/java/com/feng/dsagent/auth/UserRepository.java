package com.feng.dsagent.auth;

import java.util.Optional;
import java.util.Set;

public interface UserRepository {
    Optional<UserAccount> findByEmail(String email);
    Optional<UserAccount> findByUsername(String normalizedUsername);
    Optional<UserAccount> findById(long id);
    default Optional<UserAccount> findAnyByEmail(String email) {
        return findByEmail(email);
    }
    default Optional<UserAccount> findAnyByUsername(String normalizedUsername) {
        return findByUsername(normalizedUsername);
    }
    UserAccount create(String email, String username, String passwordHash, Set<String> roles);

    default UserAccount create(String email, String passwordHash, Set<String> roles) {
        return create(email, null, passwordHash, roles);
    }

    void updatePassword(long userId, String passwordHash);

    default void reconcileAdministrator(
        long userId,
        String email,
        String username,
        String passwordHash,
        Set<String> roles
    ) {
        throw new UnsupportedOperationException("Administrator reconciliation is not supported");
    }
}
