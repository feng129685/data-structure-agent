package com.feng.dsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:knowledge-publication-safety;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "app.knowledge.directory=src/test/resources/knowledge-fixture",
    "app.knowledge.auto-publish-local=false",
    "app.knowledge.minimum-score=4"
})
class KnowledgePublicationSafetyIntegrationTest {

    @Autowired
    private KnowledgeCorpusStats stats;

    @Autowired
    private KnowledgeSearchService search;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void doesNotPublishOrSearchLocalLessonsWithoutExplicitApproval() {
        assertThat(stats.available()).isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_chunks WHERE source_path LIKE 'textbook/%'",
            Integer.class
        )).isZero();
        assertThat(search.search(
            "单链表头插法的指针顺序", "02-linear-list", 4, KnowledgeAudience.GUEST
        )).isEmpty();
    }

    @Test
    void newKnowledgeChunksDefaultToDraft() {
        jdbc.update(
            """
            INSERT INTO knowledge_chunks (id, chapter_id, title, content, source_path)
            VALUES ('publication-default-draft', '02-linear-list', '待审核片段', '尚未审核', 'manual/pending.md')
            """
        );

        assertThat(jdbc.queryForObject(
            "SELECT review_status FROM knowledge_chunks WHERE id = 'publication-default-draft'",
            String.class
        )).isEqualTo("DRAFT");
    }
}
