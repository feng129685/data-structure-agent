package com.feng.dsagent.admin;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.modelconfig.ModelConfigService;
import com.feng.dsagent.security.AuthenticatedUser;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminService {

    private static final Set<String> ALLOWED_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    private final AdminRepository repository;
    private final ModelConfigService modelConfig;

    AdminService(AdminRepository repository, ModelConfigService modelConfig) {
        this.repository = repository;
        this.modelConfig = modelConfig;
    }

    AdminCapabilityView capabilities(AuthenticatedUser user) {
        Map<String, AdminModuleCapability> modules = new LinkedHashMap<>();
        modules.put("users", new AdminModuleCapability(true, "AVAILABLE", null));
        modules.put("audit", new AdminModuleCapability(true, "AVAILABLE", null));
        modules.put("reviewQueue", new AdminModuleCapability(true, "AVAILABLE", null));
        modules.put("backgroundTasks", new AdminModuleCapability(true, "AVAILABLE", null));
        ModelConfigService.CapabilityState modelSettings = modelConfig.capabilityState();
        modules.put(
            "modelSettings",
            new AdminModuleCapability(
                modelSettings.available(),
                modelSettings.available() ? "AVAILABLE" : "UNAVAILABLE",
                modelSettings.reason()
            )
        );
        return new AdminCapabilityView(
            user.userId(),
            user.roles().stream().sorted().toList(),
            modules,
            new AdminServiceStatus("spring", "0.0.1-SNAPSHOT", "AVAILABLE")
        );
    }

    AdminPage<AdminUserView> users(int page, int size, String search, String status, String role) {
        validatePage(page, size);
        AdminPage<AdminUser> users = repository.listUsers(new AdminUserQuery(
            page,
            size,
            normalizeOptional(search),
            parseStatus(status),
            normalizeRole(role)
        ));
        return new AdminPage<>(users.items().stream().map(this::view).toList(), page, size, users.total());
    }

    AdminUserView user(long userId) {
        return view(requireUser(userId));
    }

    @Transactional
    AdminUserView updateStatus(
        AuthenticatedUser actor,
        long targetUserId,
        String requestedStatus,
        String requestedReason,
        String requestId
    ) {
        rejectSelfMutation(actor, targetUserId);
        UserStatus status = parseRequiredStatus(requestedStatus);
        String reason = normalizeOptional(requestedReason);
        if (status == UserStatus.DISABLED && reason == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_DISABLE_REASON_REQUIRED", "禁用用户时必须填写原因");
        }
        AdminUser before = repository.lockUser(targetUserId)
            .orElseThrow(() -> userNotFound(targetUserId));
        if (before.status() == status) {
            appendAudit(actor, "USER_STATUS_CHANGED", before, before, "NO_CHANGE", requestId);
            return view(before);
        }
        if (status == UserStatus.DISABLED) {
            protectLastActiveAdministrator(before, false);
        }
        repository.updateStatus(targetUserId, status, reason);
        AdminUser after = requireUser(targetUserId);
        appendAudit(actor, "USER_STATUS_CHANGED", before, after, "SUCCESS", requestId);
        return view(after);
    }

    @Transactional
    AdminUserView updateRoles(
        AuthenticatedUser actor,
        long targetUserId,
        Set<String> requestedRoles,
        String requestId
    ) {
        rejectSelfMutation(actor, targetUserId);
        Set<String> roles = normalizeRoles(requestedRoles);
        AdminUser before = repository.lockUser(targetUserId)
            .orElseThrow(() -> userNotFound(targetUserId));
        if (before.roles().equals(roles)) {
            appendAudit(actor, "USER_ROLES_CHANGED", before, before, "NO_CHANGE", requestId);
            return view(before);
        }
        protectLastActiveAdministrator(before, roles.contains("ADMIN"));
        repository.replaceRoles(targetUserId, roles);
        AdminUser after = requireUser(targetUserId);
        appendAudit(actor, "USER_ROLES_CHANGED", before, after, "SUCCESS", requestId);
        return view(after);
    }

    AdminPage<AdminAuditEvent> auditEvents(
        int page,
        int size,
        Long actorUserId,
        String action,
        String targetType,
        String targetId,
        String from,
        String to
    ) {
        validatePage(page, size);
        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_AUDIT_TIME_RANGE_INVALID", "审计时间范围无效");
        }
        return repository.listAuditEvents(new AdminAuditQuery(
            page,
            size,
            actorUserId,
            normalizeUpper(action),
            normalizeUpper(targetType),
            normalizeOptional(targetId),
            fromInstant,
            toInstant
        ));
    }

    private void protectLastActiveAdministrator(AdminUser before, boolean keepsAdministratorRole) {
        if (before.status() != UserStatus.ACTIVE || !before.roles().contains("ADMIN") || keepsAdministratorRole) {
            return;
        }
        repository.lockActiveAdministrators();
        if (repository.activeAdministratorCount() <= 1) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "ADMIN_LAST_ADMIN_PROTECTED",
                "不能禁用或降级系统中的最后一个活跃管理员"
            );
        }
    }

    private void appendAudit(
        AuthenticatedUser actor,
        String action,
        AdminUser before,
        AdminUser after,
        String result,
        String requestId
    ) {
        repository.appendAuditEvent(new AdminAuditWrite(
            actor.userId(),
            action,
            "USER",
            Long.toString(after.id()),
            result,
            requestId == null ? "" : requestId,
            summary(before),
            summary(after)
        ));
    }

    private String summary(AdminUser user) {
        return "status=" + user.status().name() + ";roles=" + String.join(",", user.roles().stream().sorted().toList());
    }

    private AdminUser requireUser(long userId) {
        return repository.findUser(userId).orElseThrow(() -> userNotFound(userId));
    }

    private ApiException userNotFound(long userId) {
        return new ApiException(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "用户不存在");
    }

    private void rejectSelfMutation(AuthenticatedUser actor, long targetUserId) {
        if (actor.userId() == targetUserId) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_SELF_MUTATION_FORBIDDEN", "管理员不能修改自己的角色或启用状态");
        }
    }

    private UserStatus parseRequiredStatus(String value) {
        UserStatus status = parseStatus(value);
        if (status == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_USER_STATUS_INVALID", "用户状态必须是 ACTIVE 或 DISABLED");
        }
        return status;
    }

    private UserStatus parseStatus(String value) {
        String normalized = normalizeUpper(value);
        if (normalized == null) {
            return null;
        }
        try {
            return UserStatus.valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_USER_STATUS_INVALID", "用户状态必须是 ACTIVE 或 DISABLED");
        }
    }

    private String normalizeRole(String value) {
        String role = normalizeUpper(value);
        if (role == null) {
            return null;
        }
        if (!ALLOWED_ROLES.contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_ROLE_INVALID", "角色无效");
        }
        return role;
    }

    private Set<String> normalizeRoles(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_ROLE_SET_INVALID", "角色集合不能为空");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String value : values) {
            String role = normalizeRole(value);
            if (role == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_ROLE_SET_INVALID", "角色集合包含空值");
            }
            roles.add(role);
        }
        if (!roles.contains("STUDENT")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_ROLE_SET_INVALID", "所有账号必须保留 STUDENT 基础角色");
        }
        return Set.copyOf(roles);
    }

    private Instant parseInstant(String value, String parameter) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_AUDIT_TIME_INVALID", parameter + " 必须是 ISO-8601 UTC 时间");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_PAGE_INVALID", "分页参数无效");
        }
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeUpper(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private AdminUserView view(AdminUser user) {
        return new AdminUserView(
            user.id(),
            user.email(),
            user.status().name(),
            user.disabledReason(),
            user.disabledAt(),
            user.roles().stream().sorted().toList(),
            user.createdAt(),
            user.updatedAt()
        );
    }
}

record AdminCapabilityView(
    long userId,
    java.util.List<String> roles,
    Map<String, AdminModuleCapability> modules,
    AdminServiceStatus service
) {
}

record AdminModuleCapability(boolean available, String status, String reason) {
}

record AdminServiceStatus(String name, String version, String status) {
}

record AdminUserView(
    long id,
    String email,
    String status,
    String disabledReason,
    Instant disabledAt,
    java.util.List<String> roles,
    Instant createdAt,
    Instant updatedAt
) {
}
