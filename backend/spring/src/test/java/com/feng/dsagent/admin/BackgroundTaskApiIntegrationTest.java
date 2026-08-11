package com.feng.dsagent.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feng.dsagent.security.JwtTokenService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BackgroundTaskApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @BeforeEach
    void clearUsersAndAuditEvents() {
        jdbc.update("DELETE FROM background_tasks");
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void backgroundTaskOperationsAreExposedOnlyToDatabaseBackedAdministrators() throws Exception {
        long adminId = seedUser("background-admin@example.com", "STUDENT", "ADMIN");
        long studentId = seedUser("background-student@example.com", "STUDENT");

        mockMvc.perform(get("/api/v1/admin/background-tasks"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/admin/background-tasks")
                .header("Authorization", bearer(studentId, "STUDENT")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/background-tasks")
                .header("Authorization", bearer(adminId, "STUDENT", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/admin/capabilities")
                .header("Authorization", bearer(adminId, "STUDENT", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modules.backgroundTasks.available").value(true))
            .andExpect(jsonPath("$.modules.backgroundTasks.status").value("AVAILABLE"));
    }

    @Test
    void administratorsCanRecoverTimedOutTasksRetrySupportedWorkAndCancelPendingWorkWithAudit() throws Exception {
        long adminId = seedUser("background-operator@example.com", "STUDENT", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "ADMIN");
        long timedOutTaskId = seedTask("STALE_TASK_RECOVERY", "RUNNING", Instant.now().minusSeconds(60));

        mockMvc.perform(post("/api/v1/admin/background-tasks/recover-timeouts")
                .header("Authorization", adminToken)
                .header("X-Request-Id", "background-recover-test"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.taskType").value("STALE_TASK_RECOVERY"));

        long recoveryTaskId = jdbc.queryForObject("SELECT MAX(id) FROM background_tasks", Long.class);
        eventuallyExpectTaskStatusAndResultCount(adminToken, recoveryTaskId, "SUCCEEDED", 1);

        mockMvc.perform(get("/api/v1/admin/background-tasks/{id}", timedOutTaskId)
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureCode").value("BACKGROUND_TASK_TIMEOUT"));

        long failedTaskId = seedTask("STALE_TASK_RECOVERY", "FAILED", null);
        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/retry", failedTaskId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", "background-retry-test"))
            .andExpect(status().isAccepted());
        eventuallyExpectTaskStatus(adminToken, failedTaskId, "SUCCEEDED");

        long pendingTaskId = seedTask("STALE_TASK_RECOVERY", "PENDING", null);
        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/cancel", pendingTaskId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", "background-cancel-test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELED"));

        mockMvc.perform(get("/api/v1/admin/background-tasks?status=SUCCEEDED&taskType=STALE_TASK_RECOVERY")
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get("/api/v1/admin/audit-events?targetType=BACKGROUND_TASK")
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void retryPreservesTaskCreationRequestIdAndAuditsTheRetryRequestId() throws Exception {
        long adminId = seedUser("background-retry-request-id@example.com", "STUDENT", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "ADMIN");
        long taskId = seedTask(
            "STALE_TASK_RECOVERY",
            "FAILED",
            null,
            "background-task-created-for-retry"
        );

        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/retry", taskId)
            .header("Authorization", adminToken)
                .header("X-Request-Id", "background-retry-operation"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.requestId").value("background-task-created-for-retry"));

        mockMvc.perform(get("/api/v1/admin/audit-events")
                .queryParam("action", "BACKGROUND_TASK_RETRIED")
                .queryParam("targetType", "BACKGROUND_TASK")
                .queryParam("targetId", Long.toString(taskId))
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].requestId").value("background-retry-operation"));

        eventuallyExpectTaskStatus(adminToken, taskId, "SUCCEEDED");
    }

    @Test
    void cancelPreservesTaskCreationRequestIdAndAuditsTheCancelRequestId() throws Exception {
        long adminId = seedUser("background-cancel-request-id@example.com", "STUDENT", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "ADMIN");
        long taskId = seedTask(
            "STALE_TASK_RECOVERY",
            "PENDING",
            null,
            "background-task-created-for-cancel"
        );

        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/cancel", taskId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", "background-cancel-operation"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("background-task-created-for-cancel"));

        mockMvc.perform(get("/api/v1/admin/audit-events")
                .queryParam("action", "BACKGROUND_TASK_CANCELED")
                .queryParam("targetType", "BACKGROUND_TASK")
                .queryParam("targetId", Long.toString(taskId))
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].requestId").value("background-cancel-operation"));
    }

    @Test
    void backgroundTaskStateTransitionsOnlySupportStaleTaskRecovery() throws Exception {
        long adminId = seedUser("background-type-boundary@example.com", "STUDENT", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "ADMIN");
        long unsupportedFailedTaskId = seedTask("INDEX_REBUILD", "FAILED", null);
        long unsupportedPendingTaskId = seedTask("INDEX_REBUILD", "PENDING", null);

        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/retry", unsupportedFailedTaskId)
                .header("Authorization", adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADMIN_BACKGROUND_TASK_RETRY_NOT_ALLOWED"));

        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/cancel", unsupportedPendingTaskId)
                .header("Authorization", adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADMIN_BACKGROUND_TASK_CANCEL_NOT_ALLOWED"));

        mockMvc.perform(get("/api/v1/admin/background-tasks/{id}", unsupportedPendingTaskId)
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void aFailedStaleRecoveryTaskCanBeRetriedOnlyOnce() throws Exception {
        long adminId = seedUser("background-retry-once@example.com", "STUDENT", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "ADMIN");
        long taskId = seedTask("STALE_TASK_RECOVERY", "FAILED", null);

        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/retry", taskId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", "background-first-retry"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.retryCount").value(1));

        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/retry", taskId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", "background-duplicate-retry"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADMIN_BACKGROUND_TASK_RETRY_NOT_ALLOWED"));

        eventuallyExpectTaskStatus(adminToken, taskId, "SUCCEEDED");
        mockMvc.perform(get("/api/v1/admin/background-tasks/{id}", taskId)
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.retryCount").value(1));
    }

    @Test
    void aCanceledStaleRecoveryTaskCannotBeRetried() throws Exception {
        long adminId = seedUser("background-canceled-retry@example.com", "STUDENT", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "ADMIN");
        long taskId = seedTask("STALE_TASK_RECOVERY", "PENDING", null);

        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/cancel", taskId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", "background-cancel-before-retry"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELED"));

        mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/retry", taskId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", "background-retry-after-cancel"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADMIN_BACKGROUND_TASK_RETRY_NOT_ALLOWED"));

        mockMvc.perform(get("/api/v1/admin/background-tasks/{id}", taskId)
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELED"))
            .andExpect(jsonPath("$.retryCount").value(0));
    }

    @Test
    void concurrentRetriesAcceptOnlyOneStateTransition() throws Exception {
        long adminId = seedUser("background-concurrent-retry@example.com", "STUDENT", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "ADMIN");
        long taskId = seedTask("STALE_TASK_RECOVERY", "FAILED", null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService requests = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = requests.submit(
                () -> retryWhenReleased(taskId, adminToken, "background-concurrent-retry-1", ready, start)
            );
            Future<Integer> second = requests.submit(
                () -> retryWhenReleased(taskId, adminToken, "background-concurrent-retry-2", ready, start)
            );
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Integer> statuses = List.of(
                first.get(5, TimeUnit.SECONDS),
                second.get(5, TimeUnit.SECONDS)
            );
            assertEquals(1L, statuses.stream().filter(status -> status == 202).count());
            assertEquals(1L, statuses.stream().filter(status -> status == 409).count());
        } finally {
            start.countDown();
            requests.shutdownNow();
        }

        eventuallyExpectTaskStatus(adminToken, taskId, "SUCCEEDED");
        mockMvc.perform(get("/api/v1/admin/background-tasks/{id}", taskId)
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.retryCount").value(1));
    }

    private long seedUser(String email, String... roles) {
        jdbc.update(
            "INSERT INTO users (email, password_hash, status) VALUES (?, ?, 'ACTIVE')",
            email,
            "test-password-hash"
        );
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    private long seedTask(String taskType, String status, Instant deadlineAt) {
        return seedTask(taskType, status, deadlineAt, "");
    }

    private long seedTask(String taskType, String status, Instant deadlineAt, String requestId) {
        jdbc.update(
            """
                INSERT INTO background_tasks (
                    task_type, status, started_at, deadline_at, heartbeat_at, failure_code, failure_reason,
                    request_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            taskType,
            status,
            "RUNNING".equals(status) ? java.sql.Timestamp.from(Instant.now().minusSeconds(120)) : null,
            deadlineAt == null ? null : java.sql.Timestamp.from(deadlineAt),
            "RUNNING".equals(status) ? java.sql.Timestamp.from(Instant.now().minusSeconds(120)) : null,
            "FAILED".equals(status) ? "BACKGROUND_TASK_EXECUTION_FAILED" : null,
            "FAILED".equals(status) ? "Controlled test failure" : null,
            requestId
        );
        return jdbc.queryForObject("SELECT MAX(id) FROM background_tasks", Long.class);
    }

    private void eventuallyExpectTaskStatus(String adminToken, long taskId, String expectedStatus) throws Exception {
        AssertionError latest = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            try {
                mockMvc.perform(get("/api/v1/admin/background-tasks/{id}", taskId)
                        .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(expectedStatus));
                return;
            } catch (AssertionError error) {
                latest = error;
                Thread.sleep(25L);
            }
        }
        throw latest;
    }

    private void eventuallyExpectTaskStatusAndResultCount(
        String adminToken,
        long taskId,
        String expectedStatus,
        int expectedResultCount
    ) throws Exception {
        AssertionError latest = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            try {
                mockMvc.perform(get("/api/v1/admin/background-tasks/{id}", taskId)
                        .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(expectedStatus))
                    .andExpect(jsonPath("$.resultCount").value(expectedResultCount));
                return;
            } catch (AssertionError error) {
                latest = error;
                Thread.sleep(25L);
            }
        }
        throw latest;
    }

    private int retryWhenReleased(
        long taskId,
        String adminToken,
        String requestId,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Concurrent retry requests were not released");
        }
        return mockMvc.perform(post("/api/v1/admin/background-tasks/{id}/retry", taskId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", requestId))
            .andReturn()
            .getResponse()
            .getStatus();
    }

    private String bearer(long userId, String... roles) {
        return "Bearer " + tokens.issue(userId, "token-" + userId + "@example.com", Set.of(roles));
    }
}
