package com.feng.dsagent.admin;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BackgroundTaskService {

    private static final Set<String> STATUSES = Set.of(
        "PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED"
    );
    private static final String STALE_TASK_RECOVERY = "STALE_TASK_RECOVERY";
    private static final Duration TASK_EXECUTION_DEADLINE = Duration.ofMinutes(1);

    private final BackgroundTaskRepository repository;
    private final AdminRepository adminRepository;
    private final Clock clock;

    BackgroundTaskService(BackgroundTaskRepository repository, AdminRepository adminRepository, Clock clock) {
        this.repository = repository;
        this.adminRepository = adminRepository;
        this.clock = clock;
    }

    BackgroundTaskPage list(int page, int size, String requestedStatus, String requestedTaskType) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_PAGE_INVALID", "分页参数无效");
        }
        return repository.list(new BackgroundTaskQuery(
            page,
            size,
            status(requestedStatus),
            taskType(requestedTaskType)
        ));
    }

    BackgroundTaskView task(long taskId) {
        return require(taskId);
    }

    @Transactional
    public BackgroundTaskView submitTimeoutRecovery(AuthenticatedUser actor, String requestId) {
        Instant now = clock.instant();
        BackgroundTaskView task = repository.createRecoveryTask(
            actor.userId(), safeRequestId(requestId), now, now.plus(TASK_EXECUTION_DEADLINE)
        );
        appendAudit(actor, "BACKGROUND_TASK_SUBMITTED", task, task, "SUCCESS", requestId);
        return task;
    }

    @Transactional
    public BackgroundTaskView retry(AuthenticatedUser actor, long taskId, String requestId) {
        BackgroundTaskView before = repository.lock(taskId).orElseThrow(() -> notFound(taskId));
        if (!"FAILED".equals(before.status()) || !STALE_TASK_RECOVERY.equals(before.taskType())
                || before.retryCount() >= before.maxAttempts()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "ADMIN_BACKGROUND_TASK_RETRY_NOT_ALLOWED",
                "当前后台任务不能重试"
            );
        }
        repository.queueRetry(taskId, clock.instant().plus(TASK_EXECUTION_DEADLINE));
        BackgroundTaskView after = require(taskId);
        appendAudit(actor, "BACKGROUND_TASK_RETRIED", before, after, "SUCCESS", requestId);
        return after;
    }

    @Transactional
    public BackgroundTaskView cancel(AuthenticatedUser actor, long taskId, String requestId) {
        BackgroundTaskView before = repository.lock(taskId).orElseThrow(() -> notFound(taskId));
        if (!"PENDING".equals(before.status()) || !STALE_TASK_RECOVERY.equals(before.taskType())) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "ADMIN_BACKGROUND_TASK_CANCEL_NOT_ALLOWED",
                "只能取消尚未开始的后台任务"
            );
        }
        repository.cancel(taskId, clock.instant());
        BackgroundTaskView after = require(taskId);
        appendAudit(actor, "BACKGROUND_TASK_CANCELED", before, after, "SUCCESS", requestId);
        return after;
    }

    void dispatch(long taskId) {
        Thread.ofVirtual().name("background-task-" + taskId + "-").start(() -> execute(taskId));
    }

    @Scheduled(fixedDelayString = "${app.background-tasks.timeout-recovery-interval:PT30S}")
    public void recoverExpiredTasks() {
        repository.failExpired(clock.instant());
    }

    private void execute(long taskId) {
        Instant startedAt = clock.instant();
        if (!repository.start(taskId, startedAt, startedAt.plus(TASK_EXECUTION_DEADLINE))) {
            return;
        }
        try {
            int recoveredTaskCount = repository.failExpired(clock.instant());
            repository.complete(taskId, clock.instant(), recoveredTaskCount);
        } catch (RuntimeException ignored) {
            repository.fail(
                taskId,
                clock.instant(),
                "BACKGROUND_TASK_EXECUTION_FAILED",
                "Task execution did not complete"
            );
        }
    }

    private String status(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String result = normalized.toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(result)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "ADMIN_BACKGROUND_TASK_STATUS_INVALID",
                "后台任务状态无效"
            );
        }
        return result;
    }

    private String taskType(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        if (!normalized.matches("^[A-Za-z0-9_.-]{1,64}$")) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "ADMIN_BACKGROUND_TASK_TYPE_INVALID",
                "后台任务类型无效"
            );
        }
        return normalized;
    }

    private BackgroundTaskView require(long taskId) {
        if (taskId < 1) {
            throw notFound(taskId);
        }
        return repository.find(taskId).orElseThrow(() -> notFound(taskId));
    }

    private ApiException notFound(long taskId) {
        return new ApiException(HttpStatus.NOT_FOUND, "ADMIN_BACKGROUND_TASK_NOT_FOUND", "后台任务不存在");
    }

    private void appendAudit(
        AuthenticatedUser actor,
        String action,
        BackgroundTaskView before,
        BackgroundTaskView after,
        String result,
        String requestId
    ) {
        adminRepository.appendAuditEvent(new AdminAuditWrite(
            actor.userId(),
            action,
            "BACKGROUND_TASK",
            Long.toString(after.id()),
            result,
            safeRequestId(requestId),
            summary(before),
            summary(after)
        ));
    }

    private static String summary(BackgroundTaskView task) {
        return "status=" + task.status() + ";type=" + task.taskType() + ";retryCount=" + task.retryCount()
            + (task.resultCount() == null ? "" : ";resultCount=" + task.resultCount());
    }

    private static String safeRequestId(String value) {
        String normalized = normalize(value);
        return normalized == null ? "" : normalized.substring(0, Math.min(normalized.length(), 128));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
