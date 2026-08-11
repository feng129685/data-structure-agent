package com.feng.dsagent.review;

import static org.hamcrest.Matchers.containsInAnyOrder;
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
class ReviewQueueApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @BeforeEach
    void clearReviewFixtures() {
        jdbc.update("DELETE FROM content_review_events");
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("DELETE FROM dsvp_request_snapshots WHERE id LIKE 'admin-review-dsvp-%'");
        jdbc.update("DELETE FROM presentation_pages WHERE id LIKE 'admin-review-dsvp-%'");
        jdbc.update("DELETE FROM presentation_manifests WHERE id LIKE 'admin-review-dsvp-%'");
        jdbc.update("DELETE FROM knowledge_chunks WHERE id LIKE 'admin-review-%'");
        jdbc.update("DELETE FROM resources WHERE id LIKE 'admin-review-%'");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void reviewQueueRequiresAdministratorRoleAndListsRealResources() throws Exception {
        long adminId = seedUser("review-admin@example.com", "STUDENT", "TEACHER", "ADMIN");
        long studentId = seedUser("review-student@example.com", "STUDENT");
        String chapterId = publishedChapterId();
        seedResource("admin-review-resource", chapterId, "DRAFT", "Verified source", "2026.1");

        mockMvc.perform(get("/api/v1/admin/reviews"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/admin/reviews").header("Authorization", bearer(studentId, "STUDENT")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/reviews?type=RESOURCE&status=DRAFT")
                .header("Authorization", bearer(adminId, "STUDENT", "TEACHER", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].type").value("RESOURCE"))
            .andExpect(jsonPath("$.items[0].id").value("admin-review-resource"))
            .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
            .andExpect(jsonPath("$.items[0].sourceComplete").value(true));
    }

    @Test
    void verificationRequiresPublishedSourceChainAndWritesReviewAndAdminAuditHistory() throws Exception {
        long adminId = seedUser("review-history-admin@example.com", "STUDENT", "TEACHER", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");
        String chapterId = publishedChapterId();
        seedResource("admin-review-resource", chapterId, "DRAFT", "Verified source", "2026.1");
        seedKnowledge("admin-review-knowledge", chapterId, "admin-review-resource", "DRAFT");

        mockMvc.perform(get("/api/v1/admin/reviews/RESOURCE/admin-review-resource")
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.type").value("RESOURCE"))
            .andExpect(jsonPath("$.sourceChain[0].type").value("CHAPTER"))
            .andExpect(jsonPath("$.sourceChain[0].status").value("PUBLISHED"));

        mockMvc.perform(patch("/api/v1/admin/reviews/RESOURCE/admin-review-resource/status")
                .header("Authorization", adminToken)
                .header("X-Request-Id", "review-resource-verified")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\",\"note\":\"Source chain checked\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("VERIFIED"));

        mockMvc.perform(patch("/api/v1/admin/reviews/KNOWLEDGE_CHUNK/admin-review-knowledge/status")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\",\"note\":\"Citation checked\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("VERIFIED"));

        mockMvc.perform(get("/api/v1/admin/reviews/KNOWLEDGE_CHUNK/admin-review-knowledge/history")
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].previousStatus").value("DRAFT"))
            .andExpect(jsonPath("$[0].nextStatus").value("VERIFIED"))
            .andExpect(jsonPath("$[0].reviewerUserId").value(adminId));

        mockMvc.perform(get("/api/v1/admin/audit-events?targetType=RESOURCE&targetId=admin-review-resource")
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].action").value("REVIEW_STATUS_CHANGED"))
            .andExpect(jsonPath("$.items[0].requestId").value("review-resource-verified"));
    }

    @Test
    void incompleteKnowledgeSourceCannotBeMarkedVerifiedButCanBeExcluded() throws Exception {
        long adminId = seedUser("review-incomplete-admin@example.com", "STUDENT", "TEACHER", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");
        String chapterId = publishedChapterId();
        seedResource("admin-review-blocked-resource", chapterId, "DRAFT", "Unpublished source", "2026.1");
        seedKnowledge("admin-review-blocked-knowledge", chapterId, "admin-review-blocked-resource", "DRAFT");

        mockMvc.perform(patch("/api/v1/admin/reviews/KNOWLEDGE_CHUNK/admin-review-blocked-knowledge/status")
                .header("Authorization", adminToken)
                .header("X-Request-Id", "review-blocked-verified")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\",\"note\":\"Should be refused\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADMIN_REVIEW_SOURCE_INCOMPLETE"));

        mockMvc.perform(get("/api/v1/admin/audit-events?targetType=KNOWLEDGE_CHUNK&targetId=admin-review-blocked-knowledge")
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].result").value("REJECTED"))
            .andExpect(jsonPath("$.items[0].requestId").value("review-blocked-verified"));

        mockMvc.perform(patch("/api/v1/admin/reviews/KNOWLEDGE_CHUNK/admin-review-blocked-knowledge/status")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"EXCLUDED\",\"note\":\"Source is not approved\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("EXCLUDED"));

        String status = jdbc.queryForObject(
            "SELECT review_status FROM knowledge_chunks WHERE id = 'admin-review-blocked-knowledge'",
            String.class
        );
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("EXCLUDED");
    }

    @Test
    void pptBackedDsvpSnapshotCanBeVerifiedThroughPublishedPresentationSourceChain() throws Exception {
        long adminId = seedUser("review-dsvp-admin@example.com", "STUDENT", "TEACHER", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");
        String chapterId = publishedChapterId();
        String resourceId = "admin-review-dsvp-resource";
        String manifestId = "admin-review-dsvp-manifest";
        String pageId = "admin-review-dsvp-page";
        String sourceRef = "ppt/admin-review-dsvp/page-1";
        String snapshotId = "admin-review-dsvp-snapshot";

        seedResource(resourceId, chapterId, "PUBLISHED", "Review PPT source", "2026.1");
        seedPresentationManifest(manifestId, chapterId, resourceId, "PUBLISHED");
        seedPresentationPage(pageId, manifestId, sourceRef, "PUBLISHED");
        seedDsvpSnapshot(snapshotId, sourceRef);

        mockMvc.perform(patch("/api/v1/admin/reviews/DSVP_REQUEST_SNAPSHOT/" + snapshotId + "/status")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\",\"note\":\"PPT source chain checked\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("DSVP_REQUEST_SNAPSHOT"))
            .andExpect(jsonPath("$.status").value("VERIFIED"))
            .andExpect(jsonPath("$.chapterId").value(chapterId))
            .andExpect(jsonPath("$.sourceComplete").value(true));

        mockMvc.perform(get("/api/v1/admin/reviews/DSVP_REQUEST_SNAPSHOT/" + snapshotId)
                .header("Authorization", adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.sourceComplete").value(true))
            .andExpect(jsonPath("$.sourceChain[0].type").value("CHAPTER"))
            .andExpect(jsonPath("$.sourceChain[0].id").value(chapterId))
            .andExpect(jsonPath("$.sourceChain[0].status").value("PUBLISHED"))
            .andExpect(jsonPath("$.sourceChain[1].type").value("PRESENTATION_PAGE"))
            .andExpect(jsonPath("$.sourceChain[1].id").value(pageId))
            .andExpect(jsonPath("$.sourceChain[1].status").value("PUBLISHED"));
    }

    @Test
    void reviewSurfaceExcludesTypesWithoutACompletePublishedSourceChain() throws Exception {
        long adminId = seedUser("review-source-boundary-admin@example.com", "STUDENT", "TEACHER", "ADMIN");
        String adminToken = bearer(adminId, "STUDENT", "TEACHER", "ADMIN");
        String snapshotId = "admin-review-dsvp-api-snapshot";
        seedDsvpSnapshot(snapshotId, "API", "api/admin-review-dsvp/stack");

        mockMvc.perform(get("/api/v1/admin/reviews")
                .header("Authorization", adminToken)
                .param("type", "CLASSROOM_SCRIPT"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ADMIN_REVIEW_TYPE_INVALID"));

        mockMvc.perform(get("/api/v1/admin/reviews")
                .header("Authorization", adminToken)
                .param("type", "ANIMATION_OBSERVATION"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ADMIN_REVIEW_TYPE_INVALID"));

        mockMvc.perform(get("/api/v1/admin/reviews/DSVP_REQUEST_SNAPSHOT/" + snapshotId)
                .header("Authorization", adminToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ADMIN_REVIEW_NOT_FOUND"));
    }

    private long seedUser(String email, String... roles) {
        jdbc.update("INSERT INTO users (email, password_hash, status) VALUES (?, ?, 'ACTIVE')", email, "test-password-hash");
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    private String publishedChapterId() {
        return jdbc.queryForObject(
            "SELECT id FROM chapters WHERE status = 'PUBLISHED' ORDER BY chapter_number LIMIT 1",
            String.class
        );
    }

    private void seedResource(String id, String chapterId, String reviewStatus, String sourceName, String version) {
        jdbc.update(
            """
                INSERT INTO resources (id, chapter_id, resource_type, title, source_name, version_label, review_status, license_scope)
                VALUES (?, ?, 'MARKDOWN', ?, ?, ?, ?, 'CLASSROOM_ONLY')
                """,
            id,
            chapterId,
            "Review fixture " + id,
            sourceName,
            version,
            reviewStatus
        );
    }

    private void seedKnowledge(String id, String chapterId, String resourceId, String reviewStatus) {
        jdbc.update(
            """
                INSERT INTO knowledge_chunks (
                    id, chapter_id, resource_id, title, content, source_path, review_status, license_scope
                ) VALUES (?, ?, ?, ?, 'Fixture knowledge content', ?, ?, 'CLASSROOM_ONLY')
                """,
            id,
            chapterId,
            resourceId,
            "Review fixture " + id,
            "fixtures/" + id + ".md",
            reviewStatus
        );
    }

    private void seedPresentationManifest(String id, String chapterId, String resourceId, String reviewStatus) {
        jdbc.update(
            """
                INSERT INTO presentation_manifests (
                    id, chapter_id, resource_id, title, source_name, source_path, content_hash,
                    version_label, review_status, manifest_json
                ) VALUES (?, ?, ?, ?, 'Review PPT source', ?, ?, '2026.1', ?, '{}')
                """,
            id,
            chapterId,
            resourceId,
            "Review fixture " + id,
            "fixtures/" + id + ".pptx",
            "a".repeat(64),
            reviewStatus
        );
    }

    private void seedPresentationPage(String id, String manifestId, String sourceRef, String reviewStatus) {
        jdbc.update(
            """
                INSERT INTO presentation_pages (
                    id, manifest_id, page_number, title, source_ref, content_hash,
                    version_label, review_status, page_json
                ) VALUES (?, ?, 1, ?, ?, ?, '2026.1', ?, '{}')
                """,
            id,
            manifestId,
            "Review fixture " + id,
            sourceRef,
            "b".repeat(64),
            reviewStatus
        );
    }

    private void seedDsvpSnapshot(String id, String sourceRef) {
        seedDsvpSnapshot(id, "PPT", sourceRef);
    }

    private void seedDsvpSnapshot(String id, String sourceType, String sourceRef) {
        jdbc.update(
            """
                INSERT INTO dsvp_request_snapshots (
                    id, protocol_version, request_json, request_hash, source_type, source_ref,
                    version_label, review_status
                ) VALUES (?, '1.0', '{}', ?, ?, ?, '2026.1', 'DRAFT')
                """,
            id,
            "c".repeat(64),
            sourceType,
            sourceRef
        );
    }

    private String bearer(long userId, String... roles) {
        return "Bearer " + tokens.issue(userId, "review-token-" + userId + "@example.com", Set.of(roles));
    }
}
