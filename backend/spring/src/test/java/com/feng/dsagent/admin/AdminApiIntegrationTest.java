package com.feng.dsagent.admin;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.security.JwtTokenService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @BeforeEach
    void clearUsersAndAuditEvents() {
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void adminCapabilitiesRequireTheCurrentDatabaseAdminRole() throws Exception {
        long adminId = seedUser("admin-api@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        long studentId = seedUser("student-api@example.com", "ACTIVE", "STUDENT");
        long teacherId = seedUser("teacher-api@example.com", "ACTIVE", "STUDENT", "TEACHER");

        mockMvc.perform(get("/api/v1/admin/capabilities"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", bearer(studentId, "STUDENT")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", bearer(teacherId, "STUDENT", "TEACHER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        // A signed-but-stale role claim must not turn a student into an administrator.
        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", bearer(studentId, "ADMIN")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/capabilities").header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("STUDENT", "TEACHER", "ADMIN")))
            .andExpect(jsonPath("$.modules.users.available").value(true))
            .andExpect(jsonPath("$.modules.audit.available").value(true))
            .andExpect(jsonPath("$.modules.reviewQueue.available").value(true))
            .andExpect(jsonPath("$.modules.reviewQueue.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.modules.modelSettings.available").value(false))
            .andExpect(jsonPath("$.modules.modelSettings.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.modules.modelSettings.reason").value("MASTER_KEY_UNAVAILABLE"))
            .andExpect(jsonPath("$.modules.backgroundTasks.available").value(true))
            .andExpect(jsonPath("$.modules.backgroundTasks.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.modules.backgroundTasks.reason").value(nullValue()))
            .andExpect(jsonPath("$.service.name").value("spring"));
    }

    @Test
    void adminCanManageUsersAndTheDatabaseInvalidatesDisabledSessionsImmediately() throws Exception {
        long adminId = seedUser("admin-users@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        long studentId = seedUser("student-users@example.com", "ACTIVE", "STUDENT");
        String adminToken = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");
        String studentToken = bearer(studentId, "STUDENT");

        mockMvc.perform(get("/api/v1/admin/users?page=0&size=10&search=student-users")
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(studentId))
            .andExpect(jsonPath("$.items[0].email").value("student-users@example.com"))
            .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());

        mockMvc.perform(patch("/api/v1/admin/users/{id}/status", studentId)
                .header("Authorization", adminToken)
                .header("X-Request-Id", "admin-disable-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\",\"reason\":\"Repeated academic-integrity review failure\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISABLED"))
            .andExpect(jsonPath("$.disabledReason").value("Repeated academic-integrity review failure"));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", studentToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/admin/audit-events?targetType=USER&targetId=" + studentId)
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].action").value("USER_STATUS_CHANGED"))
            .andExpect(jsonPath("$.items[0].targetType").value("USER"))
            .andExpect(jsonPath("$.items[0].targetId").value(String.valueOf(studentId)))
            .andExpect(jsonPath("$.items[0].requestId").value("admin-disable-test"));
    }

    @Test
    void adminUserViewsReturnAndSearchByUsername() throws Exception {
        long adminId = seedUser("admin-username-view@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        long userId = seedUser("username-view@example.com", "ACTIVE", "STUDENT");
        jdbc.update(
            "UPDATE users SET username = ?, username_normalized = ? WHERE id = ?",
            "ACha_",
            "acha_",
            userId
        );

        mockMvc.perform(get("/api/v1/admin/users?page=0&size=10&search=acha_")
                .header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(userId))
            .andExpect(jsonPath("$.items[0].username").value("ACha_"));

        mockMvc.perform(get("/api/v1/admin/users/{id}", userId)
                .header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("ACha_"));
    }

    @Test
    void roleChangesAreAuditedAndPreserveTheBaselineStudentRole() throws Exception {
        long adminId = seedUser("admin-roles@example.com", "ACTIVE", "STUDENT", "TEACHER", "ADMIN");
        long studentId = seedUser("student-roles@example.com", "ACTIVE", "STUDENT");
        String adminToken = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");

        mockMvc.perform(patch("/api/v1/admin/users/{id}/roles", studentId)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"STUDENT\",\"TEACHER\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("STUDENT", "TEACHER")));

        mockMvc.perform(get("/api/v1/admin/users/{id}", studentId).header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("STUDENT", "TEACHER")));

        mockMvc.perform(patch("/api/v1/admin/users/{id}/roles", studentId)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"TEACHER\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ADMIN_ROLE_SET_INVALID"));

        mockMvc.perform(get("/api/v1/admin/audit-events?action=USER_ROLES_CHANGED")
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].afterSummary").value("status=ACTIVE;roles=STUDENT,TEACHER"));
    }

    private long seedUser(String email, String status, String... roles) {
        jdbc.update(
            "INSERT INTO users (email, password_hash, status) VALUES (?, ?, ?)",
            email,
            "test-password-hash",
            status
        );
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    private String bearer(long userId, String... roles) {
        return "Bearer " + tokens.issue(userId, "token-" + userId + "@example.com", Set.of(roles));
    }
}
