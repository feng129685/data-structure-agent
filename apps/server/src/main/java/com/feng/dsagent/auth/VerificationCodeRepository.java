package com.feng.dsagent.auth;

import java.time.Instant;
import java.util.Optional;

interface VerificationCodeRepository {
    void save(String email, String purpose, String codeHash, Instant expiresAt);
    Optional<VerificationCodeRecord> latestActive(String email, String purpose);
    boolean incrementAttemptsIfActive(long id, Instant now, int maximumAttempts);
    boolean consumeIfActive(long id, Instant now, int maximumAttempts);
}
