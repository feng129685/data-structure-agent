package com.feng.dsagent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.knowledge")
public record KnowledgeProperties(
    boolean enabled,
    boolean autoPublishLocal,
    String directory,
    double minimumScore,
    int searchLimit,
    int chunkSize
) {
}
