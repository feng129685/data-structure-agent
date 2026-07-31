package com.feng.dsagent.knowledge;

import java.util.Collection;
import java.util.List;

interface KnowledgeChunkRepository {

    void replaceTextbook(Collection<KnowledgeChunk> chunks);

    List<KnowledgeChunk> findPublished();
}
