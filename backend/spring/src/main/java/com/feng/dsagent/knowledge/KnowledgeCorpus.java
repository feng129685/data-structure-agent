package com.feng.dsagent.knowledge;

import java.util.List;

public record KnowledgeCorpus(List<KnowledgeChunk> chunks, KnowledgeCorpusStats stats) {

    public KnowledgeCorpus {
        chunks = List.copyOf(chunks);
    }

    static KnowledgeCorpus empty() {
        return new KnowledgeCorpus(List.of(), KnowledgeCorpusStats.empty());
    }
}
