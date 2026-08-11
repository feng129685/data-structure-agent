package com.feng.dsagent.aiquota;

import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import java.util.Objects;

/**
 * The public boundary for formal AI generation. Production beans meter every invocation;
 * direct service tests use the explicit unmetered adapter instead of mocking internal modules.
 */
@FunctionalInterface
public interface AiQuotaExecution {

    ModelResponse complete(Long userId, String operation, String requestId, ModelRequest request);

    default void stream(
        Long userId,
        String operation,
        String requestId,
        ModelRequest request,
        ModelStreamHandler handler
    ) {
        throw new UnsupportedOperationException("Streaming is not implemented by this AI execution boundary");
    }

    default void requireFormalAuthentication(Long userId) {
        // The test-only adapter deliberately preserves the legacy direct-service unit-test seam.
    }

    static AiQuotaExecution unmetered(ModelClient model) {
        Objects.requireNonNull(model, "model");
        return new AiQuotaExecution() {
            @Override
            public ModelResponse complete(Long userId, String operation, String requestId, ModelRequest request) {
                return model.complete(request);
            }

            @Override
            public void stream(
                Long userId,
                String operation,
                String requestId,
                ModelRequest request,
                ModelStreamHandler handler
            ) {
                model.stream(request, handler);
            }
        };
    }
}
