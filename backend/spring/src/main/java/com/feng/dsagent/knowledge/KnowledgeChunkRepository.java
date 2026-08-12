package com.feng.dsagent.knowledge;

import java.util.Collection;
import java.util.List;
import java.util.Set;

interface KnowledgeChunkRepository {

    void replaceTextbook(Collection<KnowledgeChunk> chunks);

    List<KnowledgeChunk> findPublished();

    /**
     * Rechecks a cached candidate set against the current database source chain
     * and the caller's allowed license scope. Implementations must fail closed.
     */
    default Set<String> findEligibleIds(Collection<String> chunkIds, KnowledgeAudience audience) {
        return Set.of();
    }
}
