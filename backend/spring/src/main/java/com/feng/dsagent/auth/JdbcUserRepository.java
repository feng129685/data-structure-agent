package com.feng.dsagent.auth;

import java.sql.PreparedStatement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        List<UserAccount> users = jdbc.query(
            "SELECT id, email, password_hash FROM users WHERE email = ? AND status = 'ACTIVE'",
            (row, index) -> account(row.getLong("id"), row.getString("email"), row.getString("password_hash")),
            email
        );
        return users.stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findById(long id) {
        List<UserAccount> users = jdbc.query(
            "SELECT id, email, password_hash FROM users WHERE id = ? AND status = 'ACTIVE'",
            (row, index) -> account(row.getLong("id"), row.getString("email"), row.getString("password_hash")),
            id
        );
        return users.stream().findFirst();
    }

    @Override
    @Transactional
    public UserAccount create(String email, String passwordHash, Set<String> roles) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (email, password_hash) VALUES (?, ?)",
                new String[] {"id"}
            );
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Database did not return a user id");
        }
        long userId = key.longValue();
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, role);
        }
        return new UserAccount(userId, email, passwordHash, roles);
    }

    @Override
    public void updatePassword(long userId, String passwordHash) {
        int updated = jdbc.update(
            "UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ACTIVE'",
            passwordHash,
            userId
        );
        if (updated != 1) {
            throw new IllegalStateException("Active user disappeared during password reset");
        }
    }

    private UserAccount account(long id, String email, String passwordHash) {
        Set<String> roles = new LinkedHashSet<>(jdbc.queryForList(
            "SELECT role FROM user_roles WHERE user_id = ? ORDER BY role",
            String.class,
            id
        ));
        return new UserAccount(id, email, passwordHash, roles);
    }
}
