package com.feng.dsagent.aiquota;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.ai-quota")
public record AiQuotaExecutionProperties(
    @DefaultValue("0") long dailyTokenQuota,
    @DefaultValue("1") int maximumConcurrentRequests,
    @DefaultValue("PT2M") Duration reservationTtl
) {

    public AiQuotaExecutionProperties {
        if (dailyTokenQuota < 0) {
            throw new IllegalArgumentException("dailyTokenQuota must not be negative");
        }
        if (maximumConcurrentRequests < 1) {
            throw new IllegalArgumentException("maximumConcurrentRequests must be positive");
        }
        if (reservationTtl == null || reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("reservationTtl must be positive");
        }
    }
}
