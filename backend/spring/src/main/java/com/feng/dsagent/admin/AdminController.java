package com.feng.dsagent.admin;

import com.feng.dsagent.common.RequestIdFilter;
import com.feng.dsagent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService admin;
    private final BackgroundTaskService backgroundTasks;

    public AdminController(AdminService admin, BackgroundTaskService backgroundTasks) {
        this.admin = admin;
        this.backgroundTasks = backgroundTasks;
    }

    @GetMapping("/capabilities")
    AdminCapabilityView capabilities(@AuthenticationPrincipal AuthenticatedUser user) {
        return admin.capabilities(user);
    }

    @GetMapping("/users")
    AdminPage<AdminUserView> users(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String role
    ) {
        return admin.users(page, size, search, status, role);
    }

    @GetMapping("/users/{id}")
    AdminUserView user(@PathVariable long id) {
        return admin.user(id);
    }

    @PatchMapping("/users/{id}/status")
    AdminUserView updateStatus(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @PathVariable long id,
        @Valid @RequestBody UserStatusRequest request,
        HttpServletRequest servletRequest
    ) {
        return admin.updateStatus(actor, id, request.status(), request.reason(), requestId(servletRequest));
    }

    @PatchMapping("/users/{id}/roles")
    AdminUserView updateRoles(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @PathVariable long id,
        @Valid @RequestBody UserRolesRequest request,
        HttpServletRequest servletRequest
    ) {
        return admin.updateRoles(actor, id, request.roles(), requestId(servletRequest));
    }

    @GetMapping("/audit-events")
    AdminPage<AdminAuditEvent> auditEvents(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) Long actorUserId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String targetType,
        @RequestParam(required = false) String targetId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to
    ) {
        return admin.auditEvents(page, size, actorUserId, action, targetType, targetId, from, to);
    }

    @GetMapping("/background-tasks")
    BackgroundTaskPage backgroundTasks(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String taskType
    ) {
        return backgroundTasks.list(page, size, status, taskType);
    }

    @GetMapping("/background-tasks/{id}")
    BackgroundTaskView backgroundTask(@PathVariable long id) {
        return backgroundTasks.task(id);
    }

    @PostMapping("/background-tasks/recover-timeouts")
    ResponseEntity<BackgroundTaskView> recoverTimedOutBackgroundTasks(
        @AuthenticationPrincipal AuthenticatedUser actor,
        HttpServletRequest servletRequest
    ) {
        BackgroundTaskView task = backgroundTasks.submitTimeoutRecovery(actor, requestId(servletRequest));
        backgroundTasks.dispatch(task.id());
        return ResponseEntity.accepted().body(task);
    }

    @PostMapping("/background-tasks/{id}/retry")
    ResponseEntity<BackgroundTaskView> retryBackgroundTask(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @PathVariable long id,
        HttpServletRequest servletRequest
    ) {
        BackgroundTaskView task = backgroundTasks.retry(actor, id, requestId(servletRequest));
        backgroundTasks.dispatch(task.id());
        return ResponseEntity.accepted().body(task);
    }

    @PostMapping("/background-tasks/{id}/cancel")
    BackgroundTaskView cancelBackgroundTask(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @PathVariable long id,
        HttpServletRequest servletRequest
    ) {
        return backgroundTasks.cancel(actor, id, requestId(servletRequest));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "" : value.toString();
    }

    public record UserStatusRequest(
        @NotBlank @Size(max = 16) String status,
        @Size(max = 500) String reason
    ) {
    }

    public record UserRolesRequest(
        @NotEmpty @Size(max = 3) Set<@NotBlank @Size(max = 32) String> roles
    ) {
    }
}
