package com.feng.dsagent.aiquota;

import java.time.Instant;
import java.time.LocalDate;

public record AiQuotaReservation(
    String id,
    long userId,
    LocalDate quotaDate,
    String requestId,
    AiQuotaReservationStatus status,
    long estimatedTokens,
    Long actualTokens,
    Instant createdAt,
    Instant expiresAt,
    Instant completedAt,
    String failureCode,
    AiQuotaUsageSource usageSource
) {
}
