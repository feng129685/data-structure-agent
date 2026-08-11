package com.feng.dsagent.aiquota;

/**
 * Distinguishes a newly reserved provider call from a retry carrying an existing idempotency key.
 */
public record AiQuotaReservationAttempt(AiQuotaReservation reservation, boolean newlyReserved) {
}
