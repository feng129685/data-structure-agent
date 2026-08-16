package com.feng.dsagent.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcVerificationCodeRepository implements VerificationCodeRepository {

    private final JdbcTemplate jdbc;

    JdbcVerificationCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String email, String purpose, String codeHash, Instant expiresAt) {
        jdbc.update(
            "INSERT INTO verification_codes (email, purpose, code_hash, expires_at) VALUES (?, ?, ?, ?)",
            email,
            purpose,
            codeHash,
            Timestamp.from(expiresAt)
        );
    }

    @Override
    public Optional<Instant> latestCreatedAt(String email, String purpose) {
        return jdbc.query(
            "SELECT created_at FROM verification_codes WHERE email = ? AND purpose = ? ORDER BY id DESC LIMIT 1",
            (row, index) -> row.getTimestamp("created_at").toInstant(),
            email,
            purpose
        ).stream().findFirst();
    }

    @Override
    public Optional<VerificationCodeRecord> latestActive(String email, String purpose) {
        List<VerificationCodeRecord> rows = jdbc.query(
            "SELECT id, code_hash, attempts, expires_at FROM verification_codes "
                + "WHERE email = ? AND purpose = ? AND consumed_at IS NULL ORDER BY id DESC LIMIT 1",
            (row, index) -> new VerificationCodeRecord(
                row.getLong("id"),
                row.getString("code_hash"),
                row.getInt("attempts"),
                row.getTimestamp("expires_at").toInstant()
            ),
            email,
            purpose
        );
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean incrementAttemptsIfActive(long id, Instant now, int maximumAttempts) {
        return jdbc.update(
            """
            UPDATE verification_codes
            SET attempts = attempts + 1
            WHERE id = ? AND consumed_at IS NULL AND expires_at > ? AND attempts < ?
            """,
            id,
            Timestamp.from(now),
            maximumAttempts
        ) == 1;
    }

    @Override
    public boolean consumeIfActive(long id, Instant now, int maximumAttempts) {
        return jdbc.update(
            """
            UPDATE verification_codes
            SET consumed_at = ?
            WHERE id = ? AND consumed_at IS NULL AND expires_at > ? AND attempts < ?
            """,
            Timestamp.from(now),
            id,
            Timestamp.from(now),
            maximumAttempts
        ) == 1;
    }
}
