package com.feng.dsagent.knowledge;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeBootstrapRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeBootstrapRunner.class);

    private final KnowledgeProperties properties;
    private final KnowledgeCorpus corpus;
    private final KnowledgeChunkRepository repository;
    private final KnowledgeSearchService search;

    KnowledgeBootstrapRunner(
        KnowledgeProperties properties,
        KnowledgeCorpus corpus,
        KnowledgeChunkRepository repository,
        KnowledgeSearchService search
    ) {
        this.properties = properties;
        this.corpus = corpus;
        this.repository = repository;
        this.search = search;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled()) {
            LOGGER.info("Knowledge indexing is disabled");
            return;
        }
        if (corpus.stats().available() && properties.autoPublishLocal()) {
            repository.replaceTextbook(corpus.chunks());
            search.replace(corpus.chunks());
            LOGGER.info(
                "Loaded {} textbook lessons into {} knowledge chunks",
                corpus.stats().lessonFiles(),
                corpus.stats().chunkCount()
            );
            return;
        }
        List<KnowledgeChunk> persisted = repository.findPublished();
        search.replace(persisted);
        if (!properties.autoPublishLocal()) {
            LOGGER.info(
                "Local textbook loading is disabled; loaded {} reviewed chunks from the database",
                persisted.size()
            );
        } else if (corpus.stats().available()) {
            LOGGER.info("Loaded {} reviewed chunks from the database", persisted.size());
        } else {
            LOGGER.warn(
                "Private textbook directory is unavailable; loaded {} published chunks from the database",
                persisted.size()
            );
        }
    }
}
