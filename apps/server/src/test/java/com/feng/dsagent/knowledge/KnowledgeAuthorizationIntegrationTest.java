package com.feng.dsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class KnowledgeAuthorizationIntegrationTest {

    @Autowired
    private KnowledgeChunkRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void linkedResourceLicenseControlsSearchVisibilityAndDraftResourcesStayOutOfTheIndex() {
        insertResource("knowledge-auth-public", "PUBLIC", "PUBLISHED");
        insertResource("knowledge-auth-classroom", "CLASSROOM_ONLY", "PUBLISHED");
        insertResource("knowledge-auth-team", "TEAM_ONLY", "PUBLISHED");
        insertResource("knowledge-auth-draft", "PUBLIC", "DRAFT");

        insertChunk("knowledge-auth-public", "knowledge-auth-public", "public/hash.md");
        insertChunk("knowledge-auth-classroom", "knowledge-auth-classroom", "classroom/hash.md");
        insertChunk("knowledge-auth-team", "knowledge-auth-team", "team/hash.md");
        insertChunk("knowledge-auth-draft", "knowledge-auth-draft", "draft/hash.md");
        insertChunk("knowledge-auth-unlinked", null, "unlinked/hash.md");

        KnowledgeSearchService search = new KnowledgeSearchService(repository.findPublished(), 4);

        assertThat(ids(search.search("哈希冲突处理", "08-search", 6, KnowledgeAudience.GUEST)))
            .containsExactly("knowledge-auth-public");
        assertThat(ids(search.search("哈希冲突处理", "08-search", 6, KnowledgeAudience.STUDENT)))
            .containsExactlyInAnyOrder("knowledge-auth-public", "knowledge-auth-classroom");
        assertThat(ids(search.search("哈希冲突处理", "08-search", 6, KnowledgeAudience.TEAM)))
            .containsExactlyInAnyOrder(
                "knowledge-auth-public",
                "knowledge-auth-classroom",
                "knowledge-auth-team",
                "knowledge-auth-unlinked"
            )
            .doesNotContain("knowledge-auth-draft");
    }

    private void insertResource(String id, String licenseScope, String reviewStatus) {
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, source_name,
                version_label, review_status, license_scope
            ) VALUES (?, '08-search', 'TEXTBOOK', ?, '', '课程组', '1.0', ?, ?)
            """,
            id,
            id,
            reviewStatus,
            licenseScope
        );
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
    }

    private List<String> ids(List<KnowledgeSearchResult> results) {
        return results.stream().map(result -> result.chunk().id()).toList();
    }
}
