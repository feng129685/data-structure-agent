package com.feng.dsagent.modelconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class HttpModelConfigConnectionTester implements ModelConfigConnectionTester {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final int MAXIMUM_RESPONSE_BYTES = 65_536;

    private final PinnedHttpsTransport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    HttpModelConfigConnectionTester(PinnedHttpsTransport transport) {
        this.transport = transport;
    }

    @Override
    public ModelConfigConnectionResult test(ModelConfigConnection connection) {
        if (connection == null
            || connection.target() == null
            || isBlank(connection.model())
            || !PinnedHttpsTransport.isSafeHeaderValue(connection.apiKey())) {
            return new ModelConfigConnectionResult(false, "CONNECTION_FAILED");
        }
        byte[] payload = payload(connection);
        if (payload == null) {
            return new ModelConfigConnectionResult(false, "CONNECTION_FAILED");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        if (usesApiKeyHeader(connection.provider())) {
            headers.put("api-key", connection.apiKey());
        } else {
            headers.put("Authorization", "Bearer " + connection.apiKey());
        }
        try (PinnedHttpsResponse response = transport.execute(
            connection.target(),
            new PinnedHttpsRequest("POST", completionPath(connection.target()), headers, payload),
            CONNECT_TIMEOUT,
            REQUEST_TIMEOUT
        )) {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return compatibleCompletion(response)
                    ? new ModelConfigConnectionResult(true, "CONNECTION_OK")
                    : new ModelConfigConnectionResult(false, "CONNECTION_RESPONSE_INVALID");
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                return new ModelConfigConnectionResult(false, "REDIRECT_REJECTED");
            }
            return new ModelConfigConnectionResult(false, "UPSTREAM_REJECTED");
        } catch (IOException error) {
            return new ModelConfigConnectionResult(false, "CONNECTION_FAILED");
        }
    }

    private byte[] payload(ModelConfigConnection connection) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", connection.model().strip());
        payload.put("messages", List.of(Map.of(
            "role", "user",
            "content", "Connection test"
        )));
        payload.put("max_tokens", 1);
        payload.put("temperature", 0);
        payload.put("stream", false);
        try {
            return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            return null;
        }
    }

    private boolean compatibleCompletion(PinnedHttpsResponse response) {
        try {
            byte[] body = response.body().readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            if (body.length > MAXIMUM_RESPONSE_BYTES) {
                return false;
            }
            JsonNode content = objectMapper.readTree(body)
                .path("choices")
                .path(0)
                .path("message")
                .path("content");
            return content.isTextual() && !content.asText().isBlank();
        } catch (Exception error) {
            return false;
        }
    }

    private String completionPath(ModelConfigResolvedTarget target) {
        String path = target.uri().getRawPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return COMPLETIONS_PATH;
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path + COMPLETIONS_PATH;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean usesApiKeyHeader(String provider) {
        if (provider == null) {
            return false;
        }
        String normalized = provider.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return "azure".equals(normalized) || "azure-openai".equals(normalized);
    }
}
