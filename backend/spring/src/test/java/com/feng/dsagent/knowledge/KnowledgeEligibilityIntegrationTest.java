package com.feng.dsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class KnowledgeEligibilityIntegrationTest {

    private static final String RESOURCE_ID = "eligibility-incomplete-source";
    private static final String CHUNK_ID = "eligibility-incomplete-source-chunk";
    private static final String RESTRICTED_RESOURCE_ID = "eligibility-restricted-source";
    private static final String RESTRICTED_CHUNK_ID = "eligibility-restricted-source-chunk";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KnowledgeSearchService search;

    @Autowired
    private KnowledgeIndexRefreshService refresh;

    @Autowired
    private KnowledgeEvidenceReadinessService readiness;

    @BeforeEach
    void prepareData() {
        cleanup();
        search.replace(List.of());
    }

    @AfterEach
    void cleanupAfterTest() {
        search.replace(List.of());
        cleanup();
    }

    @Test
    void rejectsPublishedKnowledgeWhenItsSourceChainMetadataIsIncomplete() {
        String chapterId = publishedChapterId();
        jdbc.update(
            """
                INSERT INTO resources (
                    id, chapter_id, resource_type, title, source_name, version_label, review_status, license_scope
                ) VALUES (?, ?, 'MARKDOWN', 'Incomplete source', '', '2026.1', 'VERIFIED', 'CLASSROOM_ONLY')
                """,
            RESOURCE_ID,
            chapterId
        );
        jdbc.update(
            """
                INSERT INTO knowledge_chunks (
                    id, chapter_id, resource_id, title, content, source_path, review_status, license_scope
                ) VALUES (?, ?, ?, 'Evidence evidence', 'Evidence evidence evidence from an incomplete source.',
                    'fixtures/incomplete-source.md', 'VERIFIED', 'CLASSROOM_ONLY')
                """,
            CHUNK_ID,
            chapterId,
            RESOURCE_ID
        );

        refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());

        assertThat(search.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT)).isEmpty();
        KnowledgeEvidenceReadinessService.Snapshot snapshot = readiness.snapshot(
            KnowledgeAudience.STUDENT,
            chapterId,
            "evidence"
        );
        assertThat(snapshot.availableKnowledgeChunkCount()).isZero();
        assertThat(snapshot.excludedOrUnverifiedCount()).isEqualTo(1);
    }

    @Test
    void rejectsCompletePublishedKnowledgeUntilTheSourceAndChunkAreVerified() {
        String chapterId = publishedChapterId();
        jdbc.update(
            """
                INSERT INTO resources (
                    id, chapter_id, resource_type, title, source_name, version_label, review_status, license_scope
                ) VALUES (?, ?, 'MARKDOWN', 'Published but unverified source', 'Course team', '2026.1',
                    'PUBLISHED', 'CLASSROOM_ONLY')
                """,
            RESOURCE_ID,
            chapterId
        );
        jdbc.update(
            """
                INSERT INTO knowledge_chunks (
                    id, chapter_id, resource_id, title, content, source_path, review_status, license_scope
                ) VALUES (?, ?, ?, 'Published but unverified evidence',
                    'Evidence evidence has complete metadata but has not been verified.',
                    'fixtures/published-unverified.md', 'PUBLISHED', 'CLASSROOM_ONLY')
                """,
            CHUNK_ID,
            chapterId,
            RESOURCE_ID
        );

        refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());

        assertThat(search.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT)).isEmpty();
        KnowledgeEvidenceReadinessService.Snapshot snapshot = readiness.snapshot(
            KnowledgeAudience.STUDENT,
            chapterId,
            "evidence"
        );
        assertThat(snapshot.availableKnowledgeChunkCount()).isZero();
        assertThat(snapshot.availableSourceCount()).isZero();
        assertThat(snapshot.excludedOrUnverifiedCount()).isEqualTo(1);
    }

    @Test
    void readinessUsesTheResourceAudienceScopeThatLiveSearchUses() {
        String chapterId = publishedChapterId();
        jdbc.update(
            """
                INSERT INTO resources (
                    id, chapter_id, resource_type, title, source_name, version_label, review_status, license_scope
                ) VALUES (?, ?, 'MARKDOWN', 'Teacher-only source', 'Course team', '2026.1', 'VERIFIED', 'TEAM_ONLY')
                """,
            RESTRICTED_RESOURCE_ID,
            chapterId
        );
        jdbc.update(
            """
                INSERT INTO knowledge_chunks (
                    id, chapter_id, resource_id, title, content, source_path, review_status, license_scope
                ) VALUES (?, ?, ?, 'Evidence evidence', 'Evidence evidence evidence for teachers only.',
                    'fixtures/restricted-source.md', 'VERIFIED', 'CLASSROOM_ONLY')
                """,
            RESTRICTED_CHUNK_ID,
            chapterId,
            RESTRICTED_RESOURCE_ID
        );

        refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());

        assertThat(search.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT)).isEmpty();
        KnowledgeEvidenceReadinessService.Snapshot snapshot = readiness.snapshot(
            KnowledgeAudience.STUDENT,
            chapterId,
            "evidence"
        );
        assertThat(snapshot.availableKnowledgeChunkCount()).isZero();
        assertThat(snapshot.excludedOrUnverifiedCount()).isEqualTo(1);
    }

    @Test
    void liveSearchAndReadinessRejectAnAlreadyCachedSourceAfterItsDatabaseEligibilityIsRevoked() {
        String chapterId = publishedChapterId();
        jdbc.update(
            """
                INSERT INTO resources (
                    id, chapter_id, resource_type, title, source_name, version_label, review_status, license_scope
                ) VALUES (?, ?, 'MARKDOWN', 'Live eligibility source', 'Course team', '2026.1', 'VERIFIED', 'CLASSROOM_ONLY')
                """,
            RESOURCE_ID,
            chapterId
        );
        jdbc.update(
            """
                INSERT INTO knowledge_chunks (
                    id, chapter_id, resource_id, title, content, source_path, review_status, license_scope
                ) VALUES (?, ?, ?, 'Live eligibility evidence', 'Evidence evidence remains cached until the query rechecks it.',
                    'fixtures/live-eligibility.md', 'VERIFIED', 'CLASSROOM_ONLY')
                """,
            CHUNK_ID,
            chapterId,
            RESOURCE_ID
        );
        refresh.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());

        assertThat(search.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT))
            .extracting(result -> result.chunk().id())
            .containsExactly(CHUNK_ID);

        // Simulates a separate source-chain writer that has not emitted a review event yet.
        jdbc.update("UPDATE resources SET review_status = 'DRAFT' WHERE id = ?", RESOURCE_ID);

        assertThat(search.search("evidence", chapterId, 6, KnowledgeAudience.STUDENT)).isEmpty();
        KnowledgeEvidenceReadinessService.Snapshot snapshot = readiness.snapshot(
            KnowledgeAudience.STUDENT,
            chapterId,
            "evidence"
        );
        assertThat(snapshot.availableKnowledgeChunkCount()).isZero();
        assertThat(snapshot.availableSourceCount()).isZero();
    }

    private String publishedChapterId() {
        return jdbc.queryForObject(
            "SELECT id FROM chapters WHERE status = 'PUBLISHED' ORDER BY chapter_number LIMIT 1",
            String.class
        );
    }

    private void cleanup() {
        jdbc.update("DELETE FROM knowledge_chunks WHERE id LIKE 'eligibility-%'");
        jdbc.update("DELETE FROM resources WHERE id LIKE 'eligibility-%'");
    }
}
