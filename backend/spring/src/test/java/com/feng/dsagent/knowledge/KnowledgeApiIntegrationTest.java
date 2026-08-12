package com.feng.dsagent.knowledge;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class KnowledgeApiIntegrationTest {

    private static final long STUDENT_ID = 98001L;
    private static final long TEACHER_ID = 98002L;
    private static final long LIMIT_TEACHER_ID = 98003L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private KnowledgeSearchService search;

    @Autowired
    private KnowledgeChunkRepository repository;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM knowledge_chunks WHERE id LIKE 'knowledge-api-%'");
        jdbc.update("DELETE FROM resources WHERE id LIKE 'knowledge-api-%'");
        seedUser(STUDENT_ID, "knowledge-student@example.com", "STUDENT");
        seedUser(TEACHER_ID, "knowledge-teacher@example.com", "STUDENT", "TEACHER");
        seedUser(LIMIT_TEACHER_ID, "knowledge-limit@example.com", "STUDENT", "TEACHER");
        insertResource("knowledge-api-public", "PUBLIC", "VERIFIED");
        insertResource("knowledge-api-classroom", "CLASSROOM_ONLY", "VERIFIED");
        insertResource("knowledge-api-team", "TEAM_ONLY", "VERIFIED");
        insertResource("knowledge-api-draft", "PUBLIC", "DRAFT");
        insertChunk("knowledge-api-public", "knowledge-api-public", "public/hash.md");
        insertChunk("knowledge-api-classroom", "knowledge-api-classroom", "classroom/hash.md");
        insertChunk("knowledge-api-team", "knowledge-api-team", "team/hash.md");
        insertChunk("knowledge-api-draft", "knowledge-api-draft", "draft/hash.md");
        search.replace(repository.findPublished());
    }

    @Test
    void guestSearchReturnsReviewedPublicSourcesAndMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/search")
                .param("q", "哈希冲突处理"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.query").value("哈希冲突处理"))
            .andExpect(jsonPath("$.results.length()").value(1))
            .andExpect(jsonPath("$.results[0].id").value("knowledge-api-public"))
            .andExpect(jsonPath("$.results[0].chapterId").value("08-search"))
            .andExpect(jsonPath("$.results[0].source").value("public/hash.md"))
            .andExpect(jsonPath("$.results[0].reviewStatus").value("已审核"))
            .andExpect(jsonPath("$.results[0].publicationStatus").value("PUBLISHED"))
            .andExpect(jsonPath("$.results[0].excerpt").value(containsString("哈希冲突处理")))
            .andExpect(jsonPath("$.results[0].score").isNumber());
    }

    @Test
    void authenticatedAudienceCanSearchClassroomMaterialButNotTeamMaterial() throws Exception {
        String student = tokens.issue(STUDENT_ID, "knowledge-student@example.com", Set.of("STUDENT"));
        String teacher = tokens.issue(TEACHER_ID, "knowledge-teacher@example.com", Set.of("STUDENT", "TEACHER"));

        mockMvc.perform(get("/api/v1/knowledge/search")
                .param("q", "哈希冲突处理")
                .header("Authorization", "Bearer " + student))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results.length()").value(2))
            .andExpect(content().string(containsString("knowledge-api-classroom")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("knowledge-api-team"))));

        mockMvc.perform(get("/api/v1/knowledge/search")
                .param("q", "哈希冲突处理")
                .header("Authorization", "Bearer " + teacher))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results.length()").value(3))
            .andExpect(content().string(containsString("knowledge-api-team")));
    }

    @Test
    void emptyOrOversizedQueriesHaveExplicitErrors() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/search"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("KNOWLEDGE_QUERY_REQUIRED"));

        mockMvc.perform(get("/api/v1/knowledge/search")
                .param("q", "x".repeat(501)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("KNOWLEDGE_QUERY_TOO_LONG"));
    }

    @Test
    void malformedLimitHasAnExplicitClientError() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/search")
                .param("q", "哈希冲突处理")
                .param("limit", "not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("KNOWLEDGE_LIMIT_INVALID"));
    }

    @Test
    void numericLimitIsClampedToThePublicBounds() throws Exception {
        String teacher = tokens.issue(LIMIT_TEACHER_ID, "knowledge-limit@example.com", Set.of("STUDENT", "TEACHER"));

        mockMvc.perform(get("/api/v1/knowledge/search")
                .param("q", "哈希冲突处理")
                .param("limit", "0")
                .header("Authorization", "Bearer " + teacher))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results.length()").value(1));

        mockMvc.perform(get("/api/v1/knowledge/search")
                .param("q", "哈希冲突处理")
                .param("limit", "999")
                .header("Authorization", "Bearer " + teacher))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results.length()").value(3));
    }

    private void insertResource(String id, String licenseScope, String reviewStatus) {
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, source_name,
                version_label, review_status, license_scope
            ) VALUES (?, '08-search', ?, '', '', '课程组', '1.0', ?, ?)
            """,
            id,
            id,
            reviewStatus,
            licenseScope
        );
    }

    private void seedUser(long id, String email, String... roles) {
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'hash')", id, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        }
    }

    private void insertChunk(String id, String resourceId, String sourcePath) {
        jdbc.update(
            """
            INSERT INTO knowledge_chunks (
                id, chapter_id, resource_id, title, content, source_path, review_status
            ) VALUES (?, '08-search', ?, ?, '哈希冲突处理采用开放地址法或链地址法。', ?, 'PUBLISHED')
            """,
            id,
            resourceId,
            id,
            sourcePath
        );
        jdbc.update("UPDATE knowledge_chunks SET review_status = 'VERIFIED' WHERE id = ?", id);
    }
}
