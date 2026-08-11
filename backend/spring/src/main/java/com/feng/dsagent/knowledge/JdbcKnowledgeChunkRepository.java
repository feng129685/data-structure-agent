package com.feng.dsagent.knowledge;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final JdbcTemplate jdbc;

    JdbcKnowledgeChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void replaceTextbook(Collection<KnowledgeChunk> chunks) {
        jdbc.update("DELETE FROM knowledge_chunks WHERE source_path LIKE 'textbook/%'");
        jdbc.batchUpdate(
            "INSERT INTO knowledge_chunks "
                + "(id, chapter_id, title, content, source_path, page_label, review_status, license_scope) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', 'CLASSROOM_ONLY')",
            chunks,
            100,
            (statement, chunk) -> {
                statement.setString(1, chunk.id());
                statement.setString(2, chunk.chapterId());
                statement.setString(3, chunk.title());
                statement.setString(4, chunk.content());
                statement.setString(5, chunk.source());
                statement.setString(6, chunk.pageLabel());
            }
        );
    }

    @Override
    public List<KnowledgeChunk> findPublished() {
        return jdbc.query(
            """
            SELECT k.id, k.chapter_id, k.title, k.content, k.source_path, k.page_label,
                   CASE WHEN k.resource_id IS NULL THEN k.license_scope ELSE r.license_scope END AS license_scope
            FROM knowledge_chunks k
            LEFT JOIN chapters kc ON kc.id = k.chapter_id
            LEFT JOIN resources r ON r.id = k.resource_id
            LEFT JOIN chapters rc ON rc.id = r.chapter_id
            WHERE k.review_status IN ('PUBLISHED', 'VERIFIED')
              AND (k.chapter_id IS NULL OR kc.status = 'PUBLISHED')
              AND (k.resource_id IS NULL OR (r.review_status IN ('PUBLISHED', 'VERIFIED') AND rc.status = 'PUBLISHED'))
            ORDER BY k.source_path, k.id
            """,
            (row, index) -> new KnowledgeChunk(
                row.getString("id"),
                row.getString("chapter_id"),
                row.getString("title"),
                row.getString("content"),
                row.getString("source_path"),
                row.getString("page_label"),
                row.getString("license_scope")
            )
        );
    }
}
