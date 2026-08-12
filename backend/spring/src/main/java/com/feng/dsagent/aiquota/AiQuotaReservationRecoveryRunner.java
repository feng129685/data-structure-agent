package com.feng.dsagent.aiquota;

import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Releases reservations that expired while the application was stopped during startup.
 */
@Component
public final class AiQuotaReservationRecoveryRunner implements ApplicationRunner {

    private final AiQuotaLedgerService ledger;

    public AiQuotaReservationRecoveryRunner(AiQuotaLedgerService ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public void run(ApplicationArguments args) {
        ledger.recoverExpiredReservations();
    }
}
