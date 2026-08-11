package com.feng.dsagent.compiler;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.compiler")
public record CompilerProperties(
    String pistonBaseUrl,
    Duration timeout,
    int maximumCodeLength,
    int maximumInputLength,
    int maximumOutputLength,
    int maximumConcurrentExecutions,
    int maximumConcurrentExecutionsPerClient,
    int compileTimeoutMillis,
    int runTimeoutMillis
) {

    public CompilerProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maximumCodeLength < 1 || maximumInputLength < 0 || maximumOutputLength < 1) {
            throw new IllegalArgumentException("compiler length limits are invalid");
        }
        if (maximumConcurrentExecutions < 1 || maximumConcurrentExecutionsPerClient < 1
                || maximumConcurrentExecutionsPerClient > maximumConcurrentExecutions) {
            throw new IllegalArgumentException("compiler concurrency limits are invalid");
        }
        if (compileTimeoutMillis < 1 || runTimeoutMillis < 1 || compileTimeoutMillis > 60_000 || runTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("compiler execution time limits are invalid");
        }
    }

    boolean configured() {
        return pistonBaseUrl != null && !pistonBaseUrl.isBlank();
    }

    URI executeUri() {
        if (!configured()) {
            throw new IllegalStateException("pistonBaseUrl must be configured before execution");
        }
        return URI.create(pistonBaseUrl.strip().replaceAll("/+$", "") + "/execute");
    }
}
