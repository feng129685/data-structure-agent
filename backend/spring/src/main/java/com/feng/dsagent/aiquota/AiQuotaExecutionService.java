package com.feng.dsagent.aiquota;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelClientException;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import com.feng.dsagent.modelconfig.ModelGenerationReadiness;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * One metered execution path for formal AI generation across chat, code analysis, and animation.
 */
@Service
public class AiQuotaExecutionService implements AiQuotaExecution {

    private static final long DEFAULT_ESTIMATED_OUTPUT_TOKENS = 1_024L;

    private final ModelClient model;
    private final AiQuotaLedgerService ledger;
    private final AiQuotaExecutionProperties properties;
    private final ModelGenerationReadiness modelReadiness;

    public AiQuotaExecutionService(
        ModelClient model,
        AiQuotaLedgerService ledger,
        AiQuotaExecutionProperties properties,
        ModelGenerationReadiness modelReadiness
    ) {
        this.model = Objects.requireNonNull(model, "model");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.modelReadiness = Objects.requireNonNull(modelReadiness, "modelReadiness");
    }

    @Override
    public ModelResponse complete(Long userId, String operation, String requestId, ModelRequest request) {
        requireFormalAuthentication(userId);
        Objects.requireNonNull(request, "request");
        ModelGenerationReadiness.State state = modelReadiness.current();
        long dailyTokenQuota = configuredQuota(state);
        AiQuotaReservationAttempt attempt = ledger.reserveAttempt(new AiQuotaReservationRequest(
            userId,
            dailyTokenQuota,
            estimatedTokens(request),
            properties.maximumConcurrentRequests(),
            AiQuotaRequestId.operationScoped(operation, requestId),
            properties.reservationTtl()
        ));
        if (!attempt.newlyReserved()) {
            throw existingRequest(attempt.reservation());
        }

        ModelResponse response;
        try {
            response = model.complete(request);
        } catch (ModelClientException error) {
            settleFailure(
                attempt.reservation(),
                error.code(),
                failedUsage(attempt.reservation(), error.consumedTokens(), null, false),
                error
            );
            throw error;
        } catch (RuntimeException error) {
            settleFailure(attempt.reservation(), "AI_MODEL_EXECUTION_FAILED", noUsage(), error);
            throw error;
        }

        Usage usage = successUsage(response, attempt.reservation());
        ledger.settle(attempt.reservation().id(), usage.tokens(), usage.source());
        return response;
    }

    @Override
    public void stream(
        Long userId,
        String operation,
        String requestId,
        ModelRequest request,
        ModelStreamHandler handler
    ) {
        requireFormalAuthentication(userId);
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        ModelGenerationReadiness.State state = modelReadiness.current();
        long dailyTokenQuota = configuredQuota(state);
        AiQuotaReservationAttempt attempt = ledger.reserveAttempt(new AiQuotaReservationRequest(
            userId,
            dailyTokenQuota,
            estimatedTokens(request),
            properties.maximumConcurrentRequests(),
            AiQuotaRequestId.operationScoped(operation, requestId),
            properties.reservationTtl()
        ));
        if (!attempt.newlyReserved()) {
            throw existingRequest(attempt.reservation());
        }

        StreamProgress progress = new StreamProgress();
        try {
            model.stream(request, new ModelStreamHandler() {
                @Override
                public void onContent(String content) {
                    if (content != null && !content.isEmpty()) {
                        progress.receivedContent = true;
                    }
                    handler.onContent(content);
                }

                @Override
                public void onUsage(Long totalTokens) {
                    if (totalTokens != null && totalTokens >= 0) {
                        progress.reportedTokens = totalTokens;
                    }
                    handler.onUsage(totalTokens);
                }
            });
            Usage usage = progress.reportedTokens == null
                ? new Usage(attempt.reservation().estimatedTokens(), AiQuotaUsageSource.RESERVATION_ESTIMATE)
                : new Usage(progress.reportedTokens, AiQuotaUsageSource.PROVIDER_REPORTED);
            ledger.settle(attempt.reservation().id(), usage.tokens(), usage.source());
        } catch (ModelClientException error) {
            settleFailure(
                attempt.reservation(),
                error.code(),
                failedUsage(
                    attempt.reservation(),
                    error.consumedTokens(),
                    progress.reportedTokens,
                    progress.receivedContent
                ),
                error
            );
            throw error;
        } catch (RuntimeException error) {
            settleFailure(
                attempt.reservation(),
                error instanceof AiStreamAbortedException
                    ? "AI_STREAM_CLIENT_DISCONNECTED"
                    : "AI_STREAM_EXECUTION_FAILED",
                failedUsage(attempt.reservation(), null, progress.reportedTokens, progress.receivedContent),
                error
            );
            throw error;
        }
    }

    @Override
    public void requireFormalAuthentication(Long userId) {
        if (userId == null) {
            throw new ApiException(
                HttpStatus.UNAUTHORIZED,
                "AI_QUOTA_AUTHENTICATION_REQUIRED",
                "Authentication is required for formal AI generation"
            );
        }
    }

    public Availability availability(Long userId) {
        ModelGenerationReadiness.State state = modelReadiness.current();
        if (userId == null || !state.eligible() || state.dailyTokenQuota() == null || state.dailyTokenQuota() < 1) {
            return Availability.notConfigured();
        }
        ledger.recoverExpiredReservations();
        AiQuotaAccount account = ledger.account(userId);
        long dailyTokenQuota = state.dailyTokenQuota();
        long remaining = Math.max(0L, dailyTokenQuota - account.reservedTokens() - account.consumedTokens());
        String status = remaining < 1
            ? "EXHAUSTED"
            : account.activeReservations() >= properties.maximumConcurrentRequests()
                ? "CONCURRENCY_LIMITED"
                : "AVAILABLE";
        return new Availability(dailyTokenQuota, remaining, account.activeReservations(), status, "AVAILABLE".equals(status));
    }

    private long configuredQuota(ModelGenerationReadiness.State state) {
        if (!state.eligible() || state.dailyTokenQuota() == null || state.dailyTokenQuota() < 1) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_QUOTA_NOT_CONFIGURED",
                "AI token quota is not configured for the active model"
            );
        }
        return state.dailyTokenQuota();
    }

    private long estimatedTokens(ModelRequest request) {
        long output = request.maxTokens() == null || request.maxTokens() < 1
            ? DEFAULT_ESTIMATED_OUTPUT_TOKENS
            : request.maxTokens().longValue();
        long input = request.messages().stream()
            .mapToLong(message -> Math.max(1L, (message.content().length() + 3L) / 4L))
            .sum();
        try {
            return Math.addExact(output, input);
        } catch (ArithmeticException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI_QUOTA_ESTIMATE_INVALID", "AI request is too large");
        }
    }

    private Usage successUsage(ModelResponse response, AiQuotaReservation reservation) {
        Long reportedTokens = response.totalTokens();
        if (reportedTokens != null && reportedTokens >= 0) {
            return new Usage(reportedTokens, AiQuotaUsageSource.PROVIDER_REPORTED);
        }
        return new Usage(reservation.estimatedTokens(), AiQuotaUsageSource.RESERVATION_ESTIMATE);
    }

    private void settleFailure(
        AiQuotaReservation reservation,
        String failureCode,
        Usage usage,
        RuntimeException original
    ) {
        try {
            ledger.fail(reservation.id(), usage.tokens(), failureCode, usage.source());
        } catch (RuntimeException settlementFailure) {
            original.addSuppressed(settlementFailure);
        }
    }

    private Usage failedUsage(
        AiQuotaReservation reservation,
        Long errorReportedTokens,
        Long streamedReportedTokens,
        boolean receivedContent
    ) {
        if (streamedReportedTokens != null && streamedReportedTokens >= 0) {
            return new Usage(streamedReportedTokens, AiQuotaUsageSource.PROVIDER_REPORTED);
        }
        if (errorReportedTokens != null && errorReportedTokens >= 0) {
            return new Usage(errorReportedTokens, AiQuotaUsageSource.PROVIDER_REPORTED);
        }
        if (receivedContent) {
            return new Usage(reservation.estimatedTokens(), AiQuotaUsageSource.RESERVATION_ESTIMATE);
        }
        return noUsage();
    }

    private Usage noUsage() {
        return new Usage(0L, AiQuotaUsageSource.NO_USAGE_REPORTED);
    }

    private ApiException existingRequest(AiQuotaReservation reservation) {
        String code = reservation.status() == AiQuotaReservationStatus.RESERVED
            ? "AI_QUOTA_REQUEST_IN_PROGRESS"
            : "AI_QUOTA_REQUEST_ALREADY_FINALIZED";
        String message = reservation.status() == AiQuotaReservationStatus.RESERVED
            ? "An AI request with this idempotency key is still in progress"
            : "An AI request with this idempotency key has already completed";
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private record Usage(long tokens, AiQuotaUsageSource source) {
    }

    private static final class StreamProgress {
        private boolean receivedContent;
        private Long reportedTokens;
    }

    public record Availability(
        Long dailyTokenQuota,
        Long remainingTokens,
        int activeReservations,
        String status,
        boolean allowsFormalGeneration
    ) {

        private static Availability notConfigured() {
            return new Availability(null, null, 0, "NOT_CONFIGURED", false);
        }
    }
}
