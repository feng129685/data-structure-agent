package com.feng.dsagent.auth;

import java.time.Instant;

record VerificationCodeRecord(long id, String codeHash, int attempts, Instant expiresAt) {
}
