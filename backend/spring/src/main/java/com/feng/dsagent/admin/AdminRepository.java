package com.feng.dsagent.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AdminRepository {

    private final JdbcTemplate jdbc;

    AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    AdminPage<AdminUser> listUsers(AdminUserQuery query) {
        List<Object> parameters = new ArrayList<>();
        String where = userWhere(query, parameters);
        long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users u" + where,
            Long.class,
            parameters.toArray()
        );
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(query.size());
        pageParameters.add((long) query.page() * query.size());
        List<AdminUser> items = jdbc.query(
            """
                SELECT u.id, u.email, u.status, u.disabled_reason, u.disabled_at, u.created_at, u.updated_at
                FROM users u
                %s
                ORDER BY u.id ASC
                LIMIT ? OFFSET ?
                """.formatted(where),
            (row, index) -> user(row.getLong("id"), row),
            pageParameters.toArray()
        );
        return new AdminPage<>(items, query.page(), query.size(), total);
    }

    Optional<AdminUser> findUser(long userId) {
        return jdbc.query(
            """
                SELECT u.id, u.email, u.status, u.disabled_reason, u.disabled_at, u.created_at, u.updated_at
                FROM users u WHERE u.id = ?
                """,
            (row, index) -> user(row.getLong("id"), row),
            userId
        ).stream().findFirst();
    }

    Optional<AdminUser> lockUser(long userId) {
        return jdbc.query(
            """
                SELECT u.id, u.email, u.status, u.disabled_reason, u.disabled_at, u.created_at, u.updated_at
                FROM users u WHERE u.id = ? FOR UPDATE
                """,
            (row, index) -> user(row.getLong("id"), row),
            userId
        ).stream().findFirst();
    }

    void lockActiveAdministrators() {
        jdbc.queryForList(
            """
                SELECT u.id
                FROM users u JOIN user_roles ur ON ur.user_id = u.id
                WHERE u.status = 'ACTIVE' AND ur.role = 'ADMIN'
                FOR UPDATE
                """,
            Long.class
        );
    }

    long activeAdministratorCount() {
        return jdbc.queryForObject(
            """
                SELECT COUNT(*)
                FROM users u JOIN user_roles ur ON ur.user_id = u.id
                WHERE u.status = 'ACTIVE' AND ur.role = 'ADMIN'
                """,
            Long.class
        );
    }

    void updateStatus(long userId, UserStatus status, String reason) {
        if (status == UserStatus.ACTIVE) {
            jdbc.update(
                """
                    UPDATE users
                    SET status = 'ACTIVE', disabled_reason = NULL, disabled_at = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                userId
            );
            return;
        }
        jdbc.update(
            """
                UPDATE users
                SET status = 'DISABLED', disabled_reason = ?, disabled_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
            reason,
            userId
        );
    }

    void replaceRoles(long userId, Set<String> roles) {
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        for (String role : roles.stream().sorted().toList()) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, role);
        }
        jdbc.update("UPDATE users SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", userId);
    }

    void appendAuditEvent(AdminAuditWrite event) {
        jdbc.update(
            """
                INSERT INTO admin_audit_events (
                    actor_user_id, action, target_type, target_id, result, request_id, before_summary, after_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            event.actorUserId(),
            event.action(),
            event.targetType(),
            event.targetId(),
            event.result(),
            event.requestId(),
            event.beforeSummary(),
            event.afterSummary()
        );
    }

    AdminPage<AdminAuditEvent> listAuditEvents(AdminAuditQuery query) {
        List<Object> parameters = new ArrayList<>();
        String where = auditWhere(query, parameters);
        long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_audit_events e" + where,
            Long.class,
            parameters.toArray()
        );
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(query.size());
        pageParameters.add((long) query.page() * query.size());
        List<AdminAuditEvent> items = jdbc.query(
            """
                SELECT e.id, e.actor_user_id, e.action, e.target_type, e.target_id, e.result, e.request_id,
                       e.before_summary, e.after_summary, e.created_at
                FROM admin_audit_events e
                %s
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT ? OFFSET ?
                """.formatted(where),
            (row, index) -> new AdminAuditEvent(
                row.getLong("id"),
                row.getLong("actor_user_id"),
                row.getString("action"),
                row.getString("target_type"),
                row.getString("target_id"),
                row.getString("result"),
                row.getString("request_id"),
                row.getString("before_summary"),
                row.getString("after_summary"),
                instant(row.getTimestamp("created_at"))
            ),
            pageParameters.toArray()
        );
        return new AdminPage<>(items, query.page(), query.size(), total);
    }

    private String userWhere(AdminUserQuery query, List<Object> parameters) {
        List<String> clauses = new ArrayList<>();
        if (query.search() != null) {
            clauses.add("LOWER(u.email) LIKE ?");
            parameters.add("%" + query.search().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (query.status() != null) {
            clauses.add("u.status = ?");
            parameters.add(query.status().name());
        }
        if (query.role() != null) {
            clauses.add("EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role = ?)");
            parameters.add(query.role());
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private String auditWhere(AdminAuditQuery query, List<Object> parameters) {
        List<String> clauses = new ArrayList<>();
        if (query.actorUserId() != null) {
            clauses.add("e.actor_user_id = ?");
            parameters.add(query.actorUserId());
        }
        if (query.action() != null) {
            clauses.add("e.action = ?");
            parameters.add(query.action());
        }
        if (query.targetType() != null) {
            clauses.add("e.target_type = ?");
            parameters.add(query.targetType());
        }
        if (query.targetId() != null) {
            clauses.add("e.target_id = ?");
            parameters.add(query.targetId());
        }
        if (query.from() != null) {
            clauses.add("e.created_at >= ?");
            parameters.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            clauses.add("e.created_at <= ?");
            parameters.add(Timestamp.from(query.to()));
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private AdminUser user(long id, java.sql.ResultSet row) throws java.sql.SQLException {
        return new AdminUser(
            id,
            row.getString("email"),
            UserStatus.valueOf(row.getString("status")),
            row.getString("disabled_reason"),
            instant(row.getTimestamp("disabled_at")),
            roles(id),
            instant(row.getTimestamp("created_at")),
            instant(row.getTimestamp("updated_at"))
        );
    }

    private Set<String> roles(long userId) {
        return new LinkedHashSet<>(jdbc.queryForList(
            "SELECT role FROM user_roles WHERE user_id = ? ORDER BY role",
            String.class,
            userId
        ));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

record AdminUser(
    long id,
    String email,
    UserStatus status,
    String disabledReason,
    Instant disabledAt,
    Set<String> roles,
    Instant createdAt,
    Instant updatedAt
) {
    AdminUser {
        roles = Set.copyOf(roles);
    }
}

record AdminUserQuery(int page, int size, String search, UserStatus status, String role) {
}

record AdminAuditWrite(
    long actorUserId,
    String action,
    String targetType,
    String targetId,
    String result,
    String requestId,
    String beforeSummary,
    String afterSummary
) {
}

record AdminAuditEvent(
    long id,
    long actorUserId,
    String action,
    String targetType,
    String targetId,
    String result,
    String requestId,
    String beforeSummary,
    String afterSummary,
    Instant createdAt
) {
}

record AdminAuditQuery(
    int page,
    int size,
    Long actorUserId,
    String action,
    String targetType,
    String targetId,
    Instant from,
    Instant to
) {
}

record AdminPage<T>(List<T> items, int page, int size, long total) {
}

enum UserStatus {
    ACTIVE,
    DISABLED
}
