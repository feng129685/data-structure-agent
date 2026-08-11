package com.feng.dsagent.knowledge;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEvidenceReadinessService {

    private final KnowledgeSearchService search;
    private final JdbcTemplate jdbc;

    public KnowledgeEvidenceReadinessService(KnowledgeSearchService search, JdbcTemplate jdbc) {
        this.search = search;
        this.jdbc = jdbc;
    }

    public Snapshot snapshot(KnowledgeAudience audience, String chapterId, String prompt) {
        boolean queryScoped = prompt != null && !prompt.isBlank();
        KnowledgeSearchService.EvidenceInventory inventory = queryScoped
            ? matchingInventory(prompt, chapterId, audience)
            : search.inventory(chapterId, audience);
        return new Snapshot(
            inventory.knowledgeChunkCount(),
            inventory.sourceCount(),
            inventory.sourceCount(),
            excludedOrUnverifiedCount(audience, chapterId),
            queryScoped
        );
    }

    private KnowledgeSearchService.EvidenceInventory matchingInventory(
        String prompt,
        String chapterId,
        KnowledgeAudience audience
    ) {
        List<KnowledgeSearchResult> results = search.search(prompt, chapterId, 6, audience);
        long sources = results.stream()
            .map(result -> result.chunk().source() == null ? result.chunk().id() : result.chunk().source())
            .distinct()
            .count();
        return new KnowledgeSearchService.EvidenceInventory(results.size(), Math.toIntExact(sources));
    }

    private int excludedOrUnverifiedCount(KnowledgeAudience audience, String chapterId) {
        String licenseClause = switch (audience) {
            case GUEST -> "'PUBLIC'";
            case STUDENT -> "'PUBLIC', 'CLASSROOM_ONLY'";
            case TEAM -> "'PUBLIC', 'CLASSROOM_ONLY', 'TEAM_ONLY'";
        };
        String sql = """
                SELECT COUNT(*)
                FROM knowledge_chunks k
                LEFT JOIN chapters kc ON kc.id = k.chapter_id
                LEFT JOIN resources r ON r.id = k.resource_id
                LEFT JOIN chapters rc ON rc.id = r.chapter_id
                WHERE (? IS NULL OR k.chapter_id = ?)
                  AND NOT (
                    k.review_status IN ('PUBLISHED', 'VERIFIED')
                    AND (k.chapter_id IS NULL OR kc.status = 'PUBLISHED')
                    AND (k.resource_id IS NULL OR (
                        r.review_status IN ('PUBLISHED', 'VERIFIED')
                        AND rc.status = 'PUBLISHED'
                    ))
                    AND k.license_scope IN (%s)
                )
                """.formatted(licenseClause);
        Integer count = jdbc.queryForObject(
            sql,
            Integer.class,
            chapterId,
            chapterId
        );
        return count == null ? 0 : count;
    }

    public record Snapshot(
        int availableKnowledgeChunkCount,
        int availableResourceCount,
        int availableSourceCount,
        int excludedOrUnverifiedCount,
        boolean queryScoped
    ) {

        public boolean evidenceAvailable() {
            return availableKnowledgeChunkCount > 0;
        }
    }
}
