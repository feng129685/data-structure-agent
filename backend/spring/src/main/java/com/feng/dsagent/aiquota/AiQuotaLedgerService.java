package com.feng.dsagent.aiquota;

import com.feng.dsagent.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable per-user AI token accounting. Callers reserve before a provider call and always settle or fail it.
 */
@Service
public class AiQuotaLedgerService {

    private static final Duration MAXIMUM_RESERVATION_TTL = Duration.ofHours(1);
    private static final int EXPIRY_RECOVERY_BATCH_SIZE = 500;

    private final JdbcAiQuotaLedgerRepository repository;
    private final Clock clock;

    AiQuotaLedgerService(JdbcAiQuotaLedgerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AiQuotaReservation reserve(AiQuotaReservationRequest request) {
        return reserveAttempt(request).reservation();
    }

    @Transactional
    public AiQuotaReservationAttempt reserveAttempt(AiQuotaReservationRequest request) {
        validate(request);
        Instant now = clock.instant();
        LocalDate quotaDate = quotaDate(now);

        repository.ensureConcurrencyRow(request.userId(), now);
        AiQuotaConcurrency concurrency = repository.lockConcurrency(request.userId());
        int activeReservations = recoverExpiredForUser(request.userId(), now, concurrency.activeReservations());

        repository.ensureBucket(request.userId(), quotaDate, request.dailyTokenQuota(), now);
        AiQuotaBucket bucket = repository.lockBucket(request.userId(), quotaDate, request.dailyTokenQuota(), now);
        Optional<AiQuotaReservation> existing = repository.findByRequestForUpdate(
            request.userId(), quotaDate, request.requestId().trim()
        );
        if (existing.isPresent()) {
            return new AiQuotaReservationAttempt(existing.get(), false);
        }
        if (activeReservations >= request.maximumConcurrentRequests()) {
            throw tooManyRequests("AI_QUOTA_CONCURRENCY_LIMITED", "AI 请求并发数已达到上限");
        }
        if (!hasCapacity(bucket, request.estimatedTokens())) {
            throw tooManyRequests("AI_QUOTA_EXHAUSTED", "AI 每日 token 配额不足");
        }

        AiQuotaReservation reservation = new AiQuotaReservation(
            UUID.randomUUID().toString(),
            request.userId(),
            quotaDate,
            request.requestId().trim(),
            AiQuotaReservationStatus.RESERVED,
            request.estimatedTokens(),
            null,
            now,
            now.plus(request.reservationTtl()),
            null,
            null,
            null
        );
        repository.createReservation(reservation);
        repository.reserveTokens(request.userId(), quotaDate, request.estimatedTokens(), now);
        repository.incrementConcurrency(request.userId(), now);
        return new AiQuotaReservationAttempt(reservation, true);
    }

    @Transactional
    public AiQuotaReservation settle(String reservationId, long actualTokens) {
        return settle(reservationId, actualTokens, AiQuotaUsageSource.PROVIDER_REPORTED);
    }

    @Transactional
    public AiQuotaReservation settle(String reservationId, long actualTokens, AiQuotaUsageSource usageSource) {
        requireReservationId(reservationId);
        requireActualTokens(actualTokens);
        requireUsageSource(usageSource);
        Instant now = clock.instant();
        AiQuotaReservation candidate = repository.findReservation(reservationId)
            .orElseThrow(() -> notFound(reservationId));
        repository.ensureConcurrencyRow(candidate.userId(), now);
        AiQuotaConcurrency concurrency = repository.lockConcurrency(candidate.userId());
        AiQuotaReservation reservation = repository.lockReservation(reservationId).orElseThrow(() -> notFound(reservationId));

        if (reservation.status() == AiQuotaReservationStatus.SETTLED) {
            return reservation;
        }
        AiQuotaBucket bucket = repository.lockExistingBucket(reservation.userId(), reservation.quotaDate());
        if (reservation.status() == AiQuotaReservationStatus.EXPIRED) {
            if (!repository.markExpiredSettlement(reservationId, actualTokens, usageSource, now)) {
                throw conflict("AI_QUOTA_RESERVATION_NOT_ACTIVE", "AI 配额预留状态已变化");
            }
            repository.charge(reservation.userId(), reservation.quotaDate(), actualTokens, now);
        } else if (reservation.status() == AiQuotaReservationStatus.RESERVED) {
            requireReleasable(bucket, concurrency, reservation);
            if (!repository.markSettled(reservationId, actualTokens, usageSource, now)) {
                throw conflict("AI_QUOTA_RESERVATION_NOT_ACTIVE", "AI 配额预留状态已变化");
            }
            repository.releaseAndCharge(
                reservation.userId(), reservation.quotaDate(), reservation.estimatedTokens(), actualTokens, now
            );
            repository.decrementConcurrency(reservation.userId(), now);
        } else {
            throw conflict("AI_QUOTA_RESERVATION_NOT_ACTIVE", "AI 配额预留已结束");
        }
        return repository.findReservation(reservationId).orElseThrow(() -> notFound(reservationId));
    }

    @Transactional
    public AiQuotaReservation fail(String reservationId, long actualTokens, String failureCode) {
        AiQuotaUsageSource source = actualTokens > 0
            ? AiQuotaUsageSource.PROVIDER_REPORTED
            : AiQuotaUsageSource.NO_USAGE_REPORTED;
        return fail(reservationId, actualTokens, failureCode, source);
    }

    @Transactional
    public AiQuotaReservation fail(
        String reservationId,
        long actualTokens,
        String failureCode,
        AiQuotaUsageSource usageSource
    ) {
        requireReservationId(reservationId);
        requireActualTokens(actualTokens);
        String normalizedFailureCode = normalizeFailureCode(failureCode);
        requireUsageSource(usageSource);
        Instant now = clock.instant();
        AiQuotaReservation candidate = repository.findReservation(reservationId)
            .orElseThrow(() -> notFound(reservationId));
        repository.ensureConcurrencyRow(candidate.userId(), now);
        AiQuotaConcurrency concurrency = repository.lockConcurrency(candidate.userId());
        AiQuotaReservation reservation = repository.lockReservation(reservationId).orElseThrow(() -> notFound(reservationId));

        if (reservation.status() == AiQuotaReservationStatus.FAILED) {
            return reservation;
        }
        AiQuotaBucket bucket = repository.lockExistingBucket(reservation.userId(), reservation.quotaDate());
        if (reservation.status() == AiQuotaReservationStatus.EXPIRED) {
            if (!repository.markExpiredFailure(reservationId, actualTokens, normalizedFailureCode, usageSource, now)) {
                throw conflict("AI_QUOTA_RESERVATION_NOT_ACTIVE", "AI 配额预留状态已变化");
            }
            repository.charge(reservation.userId(), reservation.quotaDate(), actualTokens, now);
        } else if (reservation.status() == AiQuotaReservationStatus.RESERVED) {
            requireReleasable(bucket, concurrency, reservation);
            if (!repository.markFailed(reservationId, actualTokens, normalizedFailureCode, usageSource, now)) {
                throw conflict("AI_QUOTA_RESERVATION_NOT_ACTIVE", "AI 配额预留状态已变化");
            }
            repository.releaseAndCharge(
                reservation.userId(), reservation.quotaDate(), reservation.estimatedTokens(), actualTokens, now
            );
            repository.decrementConcurrency(reservation.userId(), now);
        } else {
            throw conflict("AI_QUOTA_RESERVATION_NOT_ACTIVE", "AI 配额预留已结束");
        }
        return repository.findReservation(reservationId).orElseThrow(() -> notFound(reservationId));
    }

    @Transactional
    public int recoverExpiredReservations() {
        Instant now = clock.instant();
        int recovered = 0;
        List<String> candidates = repository.expiredReservationIds(now, EXPIRY_RECOVERY_BATCH_SIZE);
        for (String reservationId : candidates) {
            if (recoverExpiredReservation(reservationId, now)) {
                recovered++;
            }
        }
        return recovered;
    }

    @Transactional(readOnly = true)
    public Optional<AiQuotaReservation> findReservation(String reservationId) {
        requireReservationId(reservationId);
        return repository.findReservation(reservationId);
    }

    @Transactional(readOnly = true)
    public AiQuotaAccount account(long userId) {
        if (userId < 1) {
            throw badRequest("AI_QUOTA_USER_INVALID", "用户标识无效");
        }
        LocalDate date = quotaDate(clock.instant());
        AiQuotaBucket bucket = repository.findBucket(userId, date)
            .orElse(new AiQuotaBucket(userId, date, 0, 0, 0));
        return new AiQuotaAccount(
            userId,
            date,
            bucket.dailyTokenQuota(),
            bucket.reservedTokens(),
            bucket.consumedTokens(),
            remaining(bucket),
            repository.activeReservations(userId)
        );
    }

    private int recoverExpiredForUser(long userId, Instant now, int activeReservations) {
        int active = activeReservations;
        for (String reservationId : repository.expiredReservationIdsForUser(userId, now)) {
            AiQuotaReservation reservation = repository.lockReservation(reservationId).orElse(null);
            if (reservation == null || reservation.status() != AiQuotaReservationStatus.RESERVED
                    || reservation.expiresAt().isAfter(now)) {
                continue;
            }
            AiQuotaBucket bucket = repository.lockExistingBucket(reservation.userId(), reservation.quotaDate());
            requireReleasable(bucket, new AiQuotaConcurrency(userId, active), reservation);
            if (repository.markExpired(reservationId, now)) {
                repository.releaseAndCharge(
                    reservation.userId(), reservation.quotaDate(), reservation.estimatedTokens(), 0, now
                );
                repository.decrementConcurrency(userId, now);
                active--;
            }
        }
        return active;
    }

    private boolean recoverExpiredReservation(String reservationId, Instant now) {
        AiQuotaReservation candidate = repository.findReservation(reservationId).orElse(null);
        if (candidate == null) {
            return false;
        }
        repository.ensureConcurrencyRow(candidate.userId(), now);
        AiQuotaConcurrency concurrency = repository.lockConcurrency(candidate.userId());
        AiQuotaReservation reservation = repository.lockReservation(reservationId).orElse(null);
        if (reservation == null || reservation.status() != AiQuotaReservationStatus.RESERVED
                || reservation.expiresAt().isAfter(now)) {
            return false;
        }
        AiQuotaBucket bucket = repository.lockExistingBucket(reservation.userId(), reservation.quotaDate());
        requireReleasable(bucket, concurrency, reservation);
        if (!repository.markExpired(reservationId, now)) {
            return false;
        }
        repository.releaseAndCharge(reservation.userId(), reservation.quotaDate(), reservation.estimatedTokens(), 0, now);
        repository.decrementConcurrency(reservation.userId(), now);
        return true;
    }

    private void validate(AiQuotaReservationRequest request) {
        if (request == null) {
            throw badRequest("AI_QUOTA_REQUEST_INVALID", "AI 配额预留参数不能为空");
        }
        if (request.userId() < 1) {
            throw badRequest("AI_QUOTA_USER_INVALID", "用户标识无效");
        }
        if (request.dailyTokenQuota() < 1) {
            throw badRequest("AI_QUOTA_NOT_CONFIGURED", "AI 每日 token 配额尚未配置");
        }
        if (request.estimatedTokens() < 1) {
            throw badRequest("AI_QUOTA_ESTIMATE_INVALID", "预估 token 数必须大于零");
        }
        if (request.maximumConcurrentRequests() < 1) {
            throw badRequest("AI_QUOTA_CONCURRENCY_INVALID", "AI 并发上限必须大于零");
        }
        if (request.requestId() == null || !request.requestId().trim().matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw badRequest("AI_QUOTA_REQUEST_ID_INVALID", "AI 请求标识无效");
        }
        if (request.reservationTtl() == null || request.reservationTtl().isNegative() || request.reservationTtl().isZero()
                || request.reservationTtl().compareTo(MAXIMUM_RESERVATION_TTL) > 0) {
            throw badRequest("AI_QUOTA_RESERVATION_TTL_INVALID", "AI 配额预留时长无效");
        }
    }

    private void requireReservationId(String reservationId) {
        if (reservationId == null || !reservationId.matches("^[A-Za-z0-9-]{1,64}$")) {
            throw badRequest("AI_QUOTA_RESERVATION_ID_INVALID", "AI 配额预留标识无效");
        }
    }

    private void requireActualTokens(long actualTokens) {
        if (actualTokens < 0) {
            throw badRequest("AI_QUOTA_USAGE_INVALID", "实际 token 数不能为负数");
        }
    }

    private void requireUsageSource(AiQuotaUsageSource usageSource) {
        if (usageSource == null) {
            throw badRequest("AI_QUOTA_USAGE_SOURCE_INVALID", "AI usage source is required");
        }
    }

    private String normalizeFailureCode(String failureCode) {
        if (failureCode == null || !failureCode.trim().matches("^[A-Z0-9_]{1,96}$")) {
            throw badRequest("AI_QUOTA_FAILURE_CODE_INVALID", "AI 失败代码无效");
        }
        return failureCode.trim();
    }

    private void requireReleasable(
        AiQuotaBucket bucket,
        AiQuotaConcurrency concurrency,
        AiQuotaReservation reservation
    ) {
        if (concurrency.activeReservations() < 1 || bucket.reservedTokens() < reservation.estimatedTokens()) {
            throw new IllegalStateException("AI quota ledger invariant violated");
        }
    }

    private boolean hasCapacity(AiQuotaBucket bucket, long estimatedTokens) {
        if (bucket.consumedTokens() > bucket.dailyTokenQuota()) {
            return false;
        }
        long afterConsumption = bucket.dailyTokenQuota() - bucket.consumedTokens();
        if (bucket.reservedTokens() > afterConsumption) {
            return false;
        }
        return estimatedTokens <= afterConsumption - bucket.reservedTokens();
    }

    private long remaining(AiQuotaBucket bucket) {
        if (!hasCapacity(bucket, 0)) {
            return 0;
        }
        return bucket.dailyTokenQuota() - bucket.consumedTokens() - bucket.reservedTokens();
    }

    private LocalDate quotaDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private ApiException notFound(String reservationId) {
        return new ApiException(HttpStatus.NOT_FOUND, "AI_QUOTA_RESERVATION_NOT_FOUND", "AI 配额预留不存在");
    }

    private ApiException tooManyRequests(String code, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
