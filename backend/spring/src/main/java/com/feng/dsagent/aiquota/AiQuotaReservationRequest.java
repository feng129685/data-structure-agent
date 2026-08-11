package com.feng.dsagent.aiquota;

import java.time.Duration;

/**
 * A caller-supplied idempotency key makes a model invocation retry safe.
 */
public record AiQuotaReservationRequest(
    long userId,
    long dailyTokenQuota,
    long estimatedTokens,
    int maximumConcurrentRequests,
    String requestId,
    Duration reservationTtl
) {
}
