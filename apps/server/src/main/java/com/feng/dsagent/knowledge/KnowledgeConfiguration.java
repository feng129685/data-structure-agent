package com.feng.dsagent.knowledge;

import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeConfiguration {

    @Bean
    KnowledgeCorpusLoader knowledgeCorpusLoader() {
        return new KnowledgeCorpusLoader();
    }

    @Bean
    KnowledgeCorpus knowledgeCorpus(KnowledgeProperties properties, KnowledgeCorpusLoader loader) {
        if (!properties.enabled() || !properties.autoPublishLocal()) {
            return KnowledgeCorpus.empty();
        }
        Path directory = properties.directory() == null || properties.directory().isBlank()
            ? null
            : Path.of(properties.directory());
        return loader.load(directory, properties.chunkSize());
    }

    @Bean
    KnowledgeCorpusStats knowledgeCorpusStats(KnowledgeCorpus corpus) {
        return corpus.stats();
    }

    @Bean
    KnowledgeSearchService knowledgeSearchService(KnowledgeCorpus corpus, KnowledgeProperties properties) {
        List<KnowledgeChunk> initialChunks = properties.enabled() && properties.autoPublishLocal()
            ? corpus.chunks()
            : List.of();
        return new KnowledgeSearchService(initialChunks, properties.minimumScore());
    }
}
