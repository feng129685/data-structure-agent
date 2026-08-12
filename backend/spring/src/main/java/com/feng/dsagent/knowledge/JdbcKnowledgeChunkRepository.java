package com.feng.dsagent.knowledge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
                + "VALUES (?, ?, ?, ?, ?, ?, 'VERIFIED', 'CLASSROOM_ONLY')",
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
                   %s AS license_scope
            FROM knowledge_chunks k
            LEFT JOIN chapters kc ON kc.id = k.chapter_id
            LEFT JOIN resources r ON r.id = k.resource_id
            LEFT JOIN chapters rc ON rc.id = r.chapter_id
            WHERE %s
            ORDER BY k.source_path, k.id
            """.formatted(
                KnowledgeEligibilitySql.EFFECTIVE_LICENSE_SCOPE,
                KnowledgeEligibilitySql.REVIEWED_SOURCE_CHAIN
            ),
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

    @Override
    public Set<String> findEligibleIds(Collection<String> chunkIds, KnowledgeAudience audience) {
        if (audience == null || chunkIds == null || chunkIds.isEmpty()) {
            return Set.of();
        }
        List<String> ids = chunkIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Set.of();
        }

        Set<String> eligible = new LinkedHashSet<>();
        for (int start = 0; start < ids.size(); start += 500) {
            List<String> batch = ids.subList(start, Math.min(ids.size(), start + 500));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            String sql = """
                SELECT k.id
                FROM knowledge_chunks k
                LEFT JOIN chapters kc ON kc.id = k.chapter_id
                LEFT JOIN resources r ON r.id = k.resource_id
                LEFT JOIN chapters rc ON rc.id = r.chapter_id
                WHERE k.id IN (%s)
                  AND %s
                  AND %s IN (%s)
                """.formatted(
                placeholders,
                KnowledgeEligibilitySql.REVIEWED_SOURCE_CHAIN,
                KnowledgeEligibilitySql.EFFECTIVE_LICENSE_SCOPE,
                licenseScopes(audience)
            );
            eligible.addAll(jdbc.query(sql, (row, index) -> row.getString("id"), batch.toArray()));
        }
        return Set.copyOf(eligible);
    }

    private String licenseScopes(KnowledgeAudience audience) {
        return switch (audience) {
            case GUEST -> "'PUBLIC'";
            case STUDENT -> "'PUBLIC', 'CLASSROOM_ONLY'";
            case TEAM -> "'PUBLIC', 'CLASSROOM_ONLY', 'TEAM_ONLY'";
        };
    }
}
