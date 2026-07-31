package com.feng.dsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "app.knowledge.directory=src/test/resources/knowledge-fixture",
    "app.knowledge.auto-publish-local=true",
    "app.knowledge.chunk-size=120",
    "app.knowledge.minimum-score=4"
})
class KnowledgeBootstrapIntegrationTest {

    @Autowired
    private KnowledgeCorpusStats stats;

    @Autowired
    private KnowledgeSearchService search;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void loadsReviewedLessonChunksIntoSearchIndexAndDatabase() {
        assertThat(stats.available()).isTrue();
        assertThat(stats.lessonFiles()).isEqualTo(2);
        assertThat(stats.chunkCount()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_chunks WHERE source_path LIKE 'textbook/%'",
            Integer.class
        )).isEqualTo(stats.chunkCount());

        assertThat(search.search(
            "单链表头插法的指针顺序", "02-linear-list", 4, KnowledgeAudience.STUDENT
        ))
            .isNotEmpty()
            .allSatisfy(result -> assertThat(result.chunk().chapterId()).isEqualTo("02-linear-list"));
    }
}
