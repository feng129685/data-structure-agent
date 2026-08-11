package com.feng.dsagent.knowledge;

public record KnowledgeCorpusStats(boolean available, int lessonFiles, int chunkCount) {

    static KnowledgeCorpusStats empty() {
        return new KnowledgeCorpusStats(false, 0, 0);
    }
}
