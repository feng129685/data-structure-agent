package com.feng.dsagent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.knowledge.KnowledgeAudience;
import com.feng.dsagent.knowledge.KnowledgeChunk;
import com.feng.dsagent.knowledge.KnowledgeSearchService;
import com.feng.dsagent.security.AuthenticatedUser;
import com.feng.dsagent.security.JwtTokenService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReviewKnowledgeIndexRefreshIntegrationTest {

    private static final String CHUNK_ID = "review-index-refresh-published";
    private static final String SOURCE_ID = "review-index-refresh-source";
    private static final String SOURCE_CHUNK_ID = "review-index-refresh-source-chunk";
    private static final String VERIFIED_CHUNK_ID = "review-index-refresh-verified";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private KnowledgeSearchService knowledge;

    @BeforeEach
    void prepareData() {
        cleanup();
        knowledge.replace(List.of());
    }

    @AfterEach
    void cleanupAfterTest() {
        knowledge.replace(List.of());
        cleanup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "EXCLUDED"})
    void revokingVerifiedKnowledgeEvictsItFromTheLiveIndexWithoutRestart(String nextStatus) throws Exception {
        String chapterId = publishedChapterId();
        seedVerifiedKnowledge(CHUNK_ID, chapterId);
        long adminId = seedAdmin();

        assertThat(knowledge.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT))
            .extracting(result -> result.chunk().id())
            .containsExactly(CHUNK_ID);

        mockMvc.perform(patch("/api/v1/admin/reviews/KNOWLEDGE_CHUNK/" + CHUNK_ID + "/status")
                .header("Authorization", bearer(adminId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + nextStatus + "\",\"note\":\"Withdrawn after review\"}"))
            .andExpect(status().isOk());

        assertThat(knowledge.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT)).isEmpty();

        mockMvc.perform(get("/api/v1/ai/readiness")
                .header("Authorization", bearer(adminId))
                .param("chapterId", chapterId)
                .param("prompt", "evidence"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidenceAvailable").value(false))
            .andExpect(jsonPath("$.availableKnowledgeChunkCount").value(0))
            .andExpect(jsonPath("$.availableSourceCount").value(0));

        mockMvc.perform(post("/api/v1/chat")
                .header("Authorization", bearer(adminId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"evidence\",\"chapterId\":\"" + chapterId + "\",\"history\":[]}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CHAT_EVIDENCE_UNAVAILABLE"));
    }

    @Test
    void revokingAPublishedSourceEvictsItsVerifiedKnowledgeWithoutRestart() throws Exception {
        String chapterId = publishedChapterId();
        seedResource(SOURCE_ID, chapterId, "VERIFIED");
        seedKnowledge(SOURCE_CHUNK_ID, chapterId, SOURCE_ID, "VERIFIED");
        knowledge.replace(List.of(chunk(SOURCE_CHUNK_ID, chapterId)));
        long adminId = seedAdmin();

        assertThat(knowledge.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT))
            .extracting(result -> result.chunk().id())
            .containsExactly(SOURCE_CHUNK_ID);

        mockMvc.perform(patch("/api/v1/admin/reviews/RESOURCE/" + SOURCE_ID + "/status")
                .header("Authorization", bearer(adminId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DRAFT\",\"note\":\"Source withdrawn\"}"))
            .andExpect(status().isOk());

        assertThat(knowledge.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT)).isEmpty();
    }

    @Test
    void revertingVerifiedKnowledgeToLegacyUnverifiedEvictsItWithoutRestart() throws Exception {
        String chapterId = publishedChapterId();
        seedResource(SOURCE_ID, chapterId, "VERIFIED");
        seedKnowledge(SOURCE_CHUNK_ID, chapterId, SOURCE_ID, "VERIFIED");
        knowledge.replace(List.of(chunk(SOURCE_CHUNK_ID, chapterId)));
        long adminId = seedAdmin();

        mockMvc.perform(patch("/api/v1/admin/reviews/KNOWLEDGE_CHUNK/" + SOURCE_CHUNK_ID + "/status")
                .header("Authorization", bearer(adminId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"LEGACY_UNVERIFIED\",\"note\":\"Legacy evidence must be rechecked\"}"))
            .andExpect(status().isOk());

        assertThat(knowledge.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT)).isEmpty();
    }

    @Test
    void verifiedKnowledgeWithAVerifiedSourceBecomesSearchableWithoutRestart() throws Exception {
        String chapterId = publishedChapterId();
        seedResource(SOURCE_ID, chapterId, "VERIFIED");
        seedKnowledge(VERIFIED_CHUNK_ID, chapterId, SOURCE_ID, "DRAFT");
        long adminId = seedAdmin();

        assertThat(knowledge.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT)).isEmpty();

        mockMvc.perform(patch("/api/v1/admin/reviews/KNOWLEDGE_CHUNK/" + VERIFIED_CHUNK_ID + "/status")
                .header("Authorization", bearer(adminId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\",\"note\":\"Source chain checked\"}"))
            .andExpect(status().isOk());

        assertThat(knowledge.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT))
            .extracting(result -> result.chunk().id())
            .containsExactly(VERIFIED_CHUNK_ID);
    }

    @Test
    void rolledBackReviewChangesDoNotInvalidateTheCommittedLiveIndex() {
        String chapterId = publishedChapterId();
        seedVerifiedKnowledge(CHUNK_ID, chapterId);
        long adminId = seedAdmin();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            reviews.updateStatus(
                new AuthenticatedUser(adminId, "review-index-refresh-admin@example.com", Set.of("ADMIN")),
                "KNOWLEDGE_CHUNK",
                CHUNK_ID,
                "DRAFT",
                "Rollback verification",
                "review-index-refresh-rollback"
            );
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject(
            "SELECT review_status FROM knowledge_chunks WHERE id = ?",
            String.class,
            CHUNK_ID
        )).isEqualTo("VERIFIED");
        assertThat(knowledge.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT))
            .extracting(result -> result.chunk().id())
            .containsExactly(CHUNK_ID);
    }

    private void seedVerifiedKnowledge(String id, String chapterId) {
        jdbc.update(
            """
                INSERT INTO knowledge_chunks (
                    id, chapter_id, title, content, source_path, review_status, license_scope
                ) VALUES (?, ?, ?, ?, ?, 'VERIFIED', 'CLASSROOM_ONLY')
                """,
            id,
            chapterId,
            "Runtime evidence evidence",
            "Evidence evidence evidence must disappear immediately.",
            "fixtures/review-index-refresh.md"
        );
        knowledge.replace(List.of(chunk(id, chapterId)));
    }

    private void seedResource(String id, String chapterId, String reviewStatus) {
        jdbc.update(
            """
                INSERT INTO resources (
                    id, chapter_id, resource_type, title, source_name, version_label, review_status, license_scope
                ) VALUES (?, ?, 'MARKDOWN', ?, 'Course team', '2026.1', ?, 'CLASSROOM_ONLY')
                """,
            id,
            chapterId,
            "Review index source " + id,
            reviewStatus
        );
    }

    private void seedKnowledge(String id, String chapterId, String resourceId, String reviewStatus) {
        jdbc.update(
            """
                INSERT INTO knowledge_chunks (
                    id, chapter_id, resource_id, title, content, source_path, review_status, license_scope
                ) VALUES (?, ?, ?, 'Runtime evidence evidence', 'Evidence evidence evidence must disappear immediately.', ?, ?, 'CLASSROOM_ONLY')
                """,
            id,
            chapterId,
            resourceId,
            "fixtures/" + id + ".md",
            reviewStatus
        );
    }

    private KnowledgeChunk chunk(String id, String chapterId) {
        return new KnowledgeChunk(
            id,
            chapterId,
            "Runtime evidence evidence",
            "Evidence evidence evidence must disappear immediately.",
            "fixtures/" + id + ".md",
            null,
            "CLASSROOM_ONLY"
        );
    }

    private void cleanup() {
        jdbc.update("DELETE FROM content_review_events WHERE content_id LIKE 'review-index-refresh-%'");
        jdbc.update("DELETE FROM admin_audit_events WHERE target_id LIKE 'review-index-refresh-%'");
        jdbc.update("DELETE FROM knowledge_chunks WHERE id LIKE 'review-index-refresh-%'");
        jdbc.update("DELETE FROM resources WHERE id LIKE 'review-index-refresh-%'");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)", "review-index-refresh-admin@example.com");
        jdbc.update("DELETE FROM users WHERE email = ?", "review-index-refresh-admin@example.com");
    }

    private long seedAdmin() {
        jdbc.update(
            "INSERT INTO users (email, password_hash, status) VALUES (?, ?, 'ACTIVE')",
            "review-index-refresh-admin@example.com",
            "test-password-hash"
        );
        long id = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?",
            Long.class,
            "review-index-refresh-admin@example.com"
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ADMIN')", id);
        return id;
    }

    private String publishedChapterId() {
        return jdbc.queryForObject(
            "SELECT id FROM chapters WHERE status = 'PUBLISHED' ORDER BY chapter_number LIMIT 1",
            String.class
        );
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.issue(
            userId,
            "review-index-refresh-admin@example.com",
            Set.of("ADMIN")
        );
    }
}
