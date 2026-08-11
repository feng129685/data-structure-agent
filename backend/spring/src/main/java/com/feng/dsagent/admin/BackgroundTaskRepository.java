package com.feng.dsagent.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
class BackgroundTaskRepository {

    private final JdbcTemplate jdbc;

    BackgroundTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    BackgroundTaskPage list(BackgroundTaskQuery query) {
        List<Object> parameters = new ArrayList<>();
        String where = where(query, parameters);
        long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM background_tasks" + where,
            Long.class,
            parameters.toArray()
        );
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(query.size());
        pageParameters.add((long) query.page() * query.size());
        List<BackgroundTaskView> items = jdbc.query(
            """
                SELECT id, task_type, status, created_at, started_at, deadline_at, heartbeat_at, finished_at,
                       failure_code, failure_reason, result_count, retry_count, max_attempts, cancel_requested_at,
                       requested_by_user_id, request_id
                FROM background_tasks
                %s
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """.formatted(where),
            (row, index) -> view(row),
            pageParameters.toArray()
        );
        return new BackgroundTaskPage(items, query.page(), query.size(), total);
    }

    Optional<BackgroundTaskView> find(long taskId) {
        return jdbc.query(
            """
                SELECT id, task_type, status, created_at, started_at, deadline_at, heartbeat_at, finished_at,
                       failure_code, failure_reason, result_count, retry_count, max_attempts, cancel_requested_at,
                       requested_by_user_id, request_id
                FROM background_tasks WHERE id = ?
                """,
            (row, index) -> view(row),
            taskId
        ).stream().findFirst();
    }

    Optional<BackgroundTaskView> lock(long taskId) {
        return jdbc.query(
            """
                SELECT id, task_type, status, created_at, started_at, deadline_at, heartbeat_at, finished_at,
                       failure_code, failure_reason, result_count, retry_count, max_attempts, cancel_requested_at,
                       requested_by_user_id, request_id
                FROM background_tasks WHERE id = ? FOR UPDATE
                """,
            (row, index) -> view(row),
            taskId
        ).stream().findFirst();
    }

    BackgroundTaskView createRecoveryTask(long actorUserId, String requestId, Instant createdAt, Instant deadlineAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("task_type", "STALE_TASK_RECOVERY");
        values.put("status", "PENDING");
        values.put("created_at", Timestamp.from(createdAt));
        values.put("deadline_at", Timestamp.from(deadlineAt));
        values.put("requested_by_user_id", actorUserId);
        values.put("request_id", requestId);
        Number id = new SimpleJdbcInsert(jdbc)
            .withTableName("background_tasks")
            .usingColumns(
                "task_type", "status", "created_at", "deadline_at", "requested_by_user_id", "request_id"
            )
            .usingGeneratedKeyColumns("id")
            .executeAndReturnKey(values);
        return find(id.longValue()).orElseThrow();
    }

    boolean start(long taskId, Instant startedAt, Instant deadlineAt) {
        return jdbc.update(
            """
                UPDATE background_tasks
                SET status = 'RUNNING', started_at = ?, heartbeat_at = ?, deadline_at = ?
                WHERE id = ? AND status = 'PENDING' AND cancel_requested_at IS NULL
                """,
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
            Timestamp.from(deadlineAt),
            taskId
        ) == 1;
    }

    void complete(long taskId, Instant completedAt, int resultCount) {
        jdbc.update(
            """
                UPDATE background_tasks
                SET status = 'SUCCEEDED', finished_at = ?, heartbeat_at = ?,
                    failure_code = NULL, failure_reason = NULL, result_count = ?
                WHERE id = ? AND status = 'RUNNING'
                """,
            Timestamp.from(completedAt),
            Timestamp.from(completedAt),
            resultCount,
            taskId
        );
    }

    void fail(long taskId, Instant failedAt, String code, String reason) {
        jdbc.update(
            """
                UPDATE background_tasks
                SET status = 'FAILED', finished_at = ?, heartbeat_at = ?, failure_code = ?, failure_reason = ?
                WHERE id = ? AND status = 'RUNNING'
                """,
            Timestamp.from(failedAt),
            Timestamp.from(failedAt),
            code,
            reason,
            taskId
        );
    }

    void queueRetry(long taskId, Instant deadlineAt) {
        jdbc.update(
            """
                UPDATE background_tasks
                SET status = 'PENDING', started_at = NULL, heartbeat_at = NULL, finished_at = NULL,
                    deadline_at = ?, failure_code = NULL, failure_reason = NULL,
                    cancel_requested_at = NULL, result_count = NULL, retry_count = retry_count + 1
                WHERE id = ?
                """,
            Timestamp.from(deadlineAt),
            taskId
        );
    }

    void cancel(long taskId, Instant canceledAt) {
        jdbc.update(
            """
                UPDATE background_tasks
                SET status = 'CANCELED', cancel_requested_at = ?, finished_at = ?, heartbeat_at = ?
                WHERE id = ? AND status = 'PENDING'
                """,
            Timestamp.from(canceledAt),
            Timestamp.from(canceledAt),
            Timestamp.from(canceledAt),
            taskId
        );
    }

    int failExpired(Instant now) {
        return jdbc.update(
            """
                UPDATE background_tasks
                SET status = 'FAILED', finished_at = ?, heartbeat_at = ?,
                    failure_code = 'BACKGROUND_TASK_TIMEOUT',
                    failure_reason = 'Task exceeded its execution deadline'
                WHERE status IN ('PENDING', 'RUNNING') AND deadline_at IS NOT NULL AND deadline_at < ?
                """,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private String where(BackgroundTaskQuery query, List<Object> parameters) {
        List<String> clauses = new ArrayList<>();
        if (query.status() != null) {
            clauses.add("status = ?");
            parameters.add(query.status());
        }
        if (query.taskType() != null) {
            clauses.add("task_type = ?");
            parameters.add(query.taskType());
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static BackgroundTaskView view(java.sql.ResultSet row) throws java.sql.SQLException {
        return new BackgroundTaskView(
            row.getLong("id"),
            row.getString("task_type"),
            row.getString("status"),
            instant(row.getTimestamp("created_at")),
            instant(row.getTimestamp("started_at")),
            instant(row.getTimestamp("deadline_at")),
            instant(row.getTimestamp("heartbeat_at")),
            instant(row.getTimestamp("finished_at")),
            row.getString("failure_code"),
            row.getString("failure_reason"),
            nullableInt(row, "result_count"),
            row.getInt("retry_count"),
            row.getInt("max_attempts"),
            instant(row.getTimestamp("cancel_requested_at")),
            nullableLong(row, "requested_by_user_id"),
            row.getString("request_id")
        );
    }

    private static Long nullableLong(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Integer nullableInt(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

record BackgroundTaskQuery(int page, int size, String status, String taskType) {
}

record BackgroundTaskPage(List<BackgroundTaskView> items, int page, int size, long total) {
}

record BackgroundTaskView(
    long id,
    String taskType,
    String status,
    Instant createdAt,
    Instant startedAt,
    Instant deadlineAt,
    Instant heartbeatAt,
    Instant finishedAt,
    String failureCode,
    String failureReason,
    Integer resultCount,
    int retryCount,
    int maxAttempts,
    Instant cancelRequestedAt,
    Long requestedByUserId,
    String requestId
) {
}
