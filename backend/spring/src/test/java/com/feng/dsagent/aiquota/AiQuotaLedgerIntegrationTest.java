package com.feng.dsagent.aiquota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.DsAgentServerApplication;
import com.feng.dsagent.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = DsAgentServerApplication.class)
@Import(AiQuotaLedgerIntegrationTest.FixedClockConfiguration.class)
class AiQuotaLedgerIntegrationTest {

    @Autowired
    private AiQuotaLedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetLedger() {
        jdbc.update("DELETE FROM ai_quota_reservations");
        jdbc.update("DELETE FROM ai_quota_buckets");
        jdbc.update("DELETE FROM ai_quota_user_concurrency");
        clock.set(Instant.parse("2026-08-11T08:00:00Z"));
    }

    @Test
    void reserveAndSettlementPersistTheDailyTokenLedger() {
        long userId = userId();

        AiQuotaReservation reserved = ledger.reserve(request(userId, 100, 30, 2, "chat-1", Duration.ofMinutes(5)));

        assertThat(reserved.status()).isEqualTo(AiQuotaReservationStatus.RESERVED);
        assertThat(ledger.account(userId)).isEqualTo(new AiQuotaAccount(
            userId,
            java.time.LocalDate.of(2026, 8, 11),
            100,
            30,
            0,
            70,
            1
        ));

        AiQuotaReservation settled = ledger.settle(reserved.id(), 24);

        assertThat(settled.status()).isEqualTo(AiQuotaReservationStatus.SETTLED);
        assertThat(settled.actualTokens()).isEqualTo(24);
        assertThat(ledger.account(userId)).isEqualTo(new AiQuotaAccount(
            userId,
            java.time.LocalDate.of(2026, 8, 11),
            100,
            0,
            24,
            76,
            0
        ));
    }

    @Test
    void duplicateRequestIsIdempotentAndConcurrencyCapIsEnforcedPerUser() {
        long userId = userId();

        AiQuotaReservation first = ledger.reserve(request(userId, 100, 20, 1, "chat-1", Duration.ofMinutes(5)));
        AiQuotaReservation duplicate = ledger.reserve(request(userId, 100, 20, 1, "chat-1", Duration.ofMinutes(5)));

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> ledger.reserve(request(userId, 100, 20, 1, "chat-2", Duration.ofMinutes(5))))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code()).isEqualTo("AI_QUOTA_CONCURRENCY_LIMITED")
            );
    }

    @Test
    void failureRecordsActualUsageAndReleasesTheReservation() {
        long userId = userId();
        AiQuotaReservation reserved = ledger.reserve(request(userId, 100, 30, 2, "analysis-1", Duration.ofMinutes(5)));

        AiQuotaReservation failed = ledger.fail(reserved.id(), 7, "MODEL_REQUEST_TIMEOUT");

        assertThat(failed.status()).isEqualTo(AiQuotaReservationStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("MODEL_REQUEST_TIMEOUT");
        assertThat(failed.actualTokens()).isEqualTo(7);
        assertThat(ledger.account(userId).reservedTokens()).isZero();
        assertThat(ledger.account(userId).consumedTokens()).isEqualTo(7);
        assertThat(ledger.account(userId).activeReservations()).isZero();
    }

    @Test
    void expiredReservationsAreRecoveredAndNoLongerBlockTheUser() {
        long userId = userId();
        AiQuotaReservation reserved = ledger.reserve(request(userId, 100, 40, 1, "stream-1", Duration.ofMinutes(1)));
        clock.advance(Duration.ofMinutes(2));

        assertThat(ledger.recoverExpiredReservations()).isEqualTo(1);
        assertThat(ledger.findReservation(reserved.id()))
            .hasValueSatisfying(expired -> {
                assertThat(expired.status()).isEqualTo(AiQuotaReservationStatus.EXPIRED);
                assertThat(expired.failureCode()).isEqualTo("AI_QUOTA_RESERVATION_EXPIRED");
            });
        assertThat(ledger.account(userId).reservedTokens()).isZero();
        assertThat(ledger.account(userId).activeReservations()).isZero();
        assertThat(ledger.reserve(request(userId, 100, 40, 1, "stream-2", Duration.ofMinutes(1))).status())
            .isEqualTo(AiQuotaReservationStatus.RESERVED);
    }

    @Test
    void concurrentReservationsCannotOversubscribeTheSameDailyBucket() throws Exception {
        long userId = userId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Object>> calls = List.of(
                reserveWhenReleased(userId, "parallel-1", ready, start),
                reserveWhenReleased(userId, "parallel-2", ready, start)
            );
            List<Future<Object>> futures = calls.stream().map(executor::submit).toList();
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = futures.stream().map(this::result).toList();

            assertThat(outcomes.stream().filter(AiQuotaReservation.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(ApiException.class::isInstance))
                .allSatisfy(value -> assertThat(((ApiException) value).code()).isEqualTo("AI_QUOTA_EXHAUSTED"));
            assertThat(ledger.account(userId).reservedTokens()).isEqualTo(60);
            assertThat(ledger.account(userId).activeReservations()).isEqualTo(1);
        }
    }

    private Callable<Object> reserveWhenReleased(long userId, String requestId, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return ledger.reserve(request(userId, 100, 60, 2, requestId, Duration.ofMinutes(5)));
            } catch (ApiException error) {
                return error;
            }
        };
    }

    private Object result(Future<Object> future) {
        try {
            return future.get();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private AiQuotaReservationRequest request(
        long userId,
        long quota,
        long estimate,
        int concurrency,
        String requestId,
        Duration ttl
    ) {
        return new AiQuotaReservationRequest(userId, quota, estimate, concurrency, requestId, ttl);
    }

    private long userId() {
        String email = "ai-quota-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-08-11T08:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
