package com.feng.dsagent.aiquota;

import java.time.LocalDate;

public record AiQuotaAccount(
    long userId,
    LocalDate quotaDate,
    long dailyTokenQuota,
    long reservedTokens,
    long consumedTokens,
    long remainingTokens,
    int activeReservations
) {
}
