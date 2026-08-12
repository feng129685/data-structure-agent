package com.feng.dsagent.modelconfig;

import static com.feng.dsagent.model.ModelErrorCode.MODEL_EMPTY_RESPONSE;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_NOT_CONFIGURED;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_REQUEST_FAILED;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_REQUEST_TIMEOUT;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_RESPONSE_READ_FAILED;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_RESPONSE_TOO_LARGE;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_STREAM_IDLE_TIMEOUT;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_STREAM_READ_FAILED;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_UPSTREAM_ERROR;

import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelClientException;
import com.feng.dsagent.model.ModelErrorCode;
import com.feng.dsagent.model.ModelMessage;
import com.feng.dsagent.model.ModelProperties;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class PinnedOpenAiCompatibleModelClient implements ModelClient {

    private static final String COMPLETIONS_PATH = "/chat/completions";

    private final ModelConfigRuntimeSettings settings;
    private final ModelProperties defaults;
    private final ObjectMapper objectMapper;
    private final PinnedHttpsTransport transport;

    PinnedOpenAiCompatibleModelClient(
        ModelConfigRuntimeSettings settings,
        ModelProperties defaults,
        ObjectMapper objectMapper,
        PinnedHttpsTransport transport
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        try (PinnedHttpsResponse response = send(request, false)) {
            if (!isSuccess(response.statusCode())) {
                throw failure(MODEL_UPSTREAM_ERROR, reportedTotalTokens(readResponseBody(response.body())));
            }
            String responseBody = readResponseBody(response.body());
            String content;
            Long totalTokens;
            try {
                JsonNode payload = objectMapper.readTree(responseBody);
                content = payload
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();
                totalTokens = reportedTotalTokens(payload);
            } catch (Exception error) {
                throw failure(MODEL_RESPONSE_READ_FAILED);
            }
            if (content == null || content.isBlank()) {
                throw failure(MODEL_EMPTY_RESPONSE);
            }
            return new ModelResponse(content, totalTokens);
        } catch (ModelClientException error) {
            throw error;
        } catch (IOException error) {
            throw failure(MODEL_RESPONSE_READ_FAILED);
        }
    }

    @Override
    public void stream(ModelRequest request, ModelStreamHandler handler) {
        Objects.requireNonNull(handler, "handler");
        try (PinnedHttpsResponse response = send(request, true)) {
            if (!isSuccess(response.statusCode())) {
                throw failure(MODEL_UPSTREAM_ERROR, reportedTotalTokens(readResponseBody(response.body())));
            }
            response.setReadTimeout(streamIdleTimeout());
            readStream(response.body(), handler);
        } catch (ModelClientException error) {
            throw error;
        } catch (SocketTimeoutException error) {
            throw failure(MODEL_STREAM_IDLE_TIMEOUT);
        } catch (IOException error) {
            throw failure(MODEL_STREAM_READ_FAILED);
        }
    }

    private PinnedHttpsResponse send(ModelRequest request, boolean stream) {
        Objects.requireNonNull(request, "request");
        String apiKey = requiredApiKey();
        String payload = serialize(request, stream);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", stream ? "text/event-stream" : "application/json");
        if (usesApiKeyHeader()) {
            headers.put("api-key", apiKey);
        } else {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        int attempt = 0;
        while (true) {
            try {
                return transport.execute(
                    settings.target(),
                    new PinnedHttpsRequest("POST", endpointPath(), headers, payloadBytes),
                    requestTimeout(),
                    requestTimeout()
                );
            } catch (SocketTimeoutException error) {
                if (!retry(attempt++)) {
                    throw failure(MODEL_REQUEST_TIMEOUT);
                }
            } catch (IOException error) {
                if (!retry(attempt++)) {
                    throw failure(MODEL_REQUEST_FAILED);
                }
            }
        }
    }

    private boolean retry(int attempt) {
        if (attempt >= settings.retryCount()) {
            return false;
        }
        long delay = Math.min(1_000L, 100L << Math.min(attempt, 3));
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(MODEL_REQUEST_FAILED);
        }
    }

    private boolean usesApiKeyHeader() {
        String provider = settings.provider();
        if (provider == null) {
            return false;
        }
        String normalized = provider.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return "azure".equals(normalized) || "azure-openai".equals(normalized);
    }

    private String serialize(ModelRequest request, boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>(request.messages().size());
        for (ModelMessage message : request.messages()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content());
            messages.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", requiredModelName());
        payload.put("messages", messages);
        payload.put("temperature", request.temperature() == null ? settings.temperature() : request.temperature());
        int configuredMaxTokens = settings.maxOutputTokens();
        int requestedMaxTokens = request.maxTokens() == null || request.maxTokens() < 1
            ? configuredMaxTokens
            : Math.min(request.maxTokens(), configuredMaxTokens);
        payload.put("max_tokens", requestedMaxTokens);
        payload.put("stream", stream);
        if (stream) {
            payload.put("stream_options", Map.of("include_usage", true));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw failure(MODEL_REQUEST_FAILED);
        }
    }

    private void readStream(InputStream input, ModelStreamHandler handler) {
        StringBuilder receivedContent = new StringBuilder();
        InputStream limitedInput = new ResponseSizeLimitedInputStream(input, maximumResponseBytes());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(limitedInput, StandardCharsets.UTF_8))) {
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw failure(MODEL_STREAM_READ_FAILED);
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    if (receivedContent.toString().isBlank()) {
                        throw failure(MODEL_EMPTY_RESPONSE);
                    }
                    return;
                }
                if (data.isEmpty()) {
                    continue;
                }
                JsonNode event = streamEvent(data);
                Long totalTokens = reportedTotalTokens(event);
                if (totalTokens != null) {
                    handler.onUsage(totalTokens);
                }
                String content = streamContent(event);
                if (content == null || content.isEmpty()) {
                    continue;
                }
                receivedContent.append(content);
                handler.onContent(content);
            }
        } catch (ModelClientException error) {
            throw error;
        } catch (ResponseTooLargeException error) {
            throw failure(MODEL_RESPONSE_TOO_LARGE);
        } catch (SocketTimeoutException error) {
            throw failure(MODEL_STREAM_IDLE_TIMEOUT);
        } catch (IOException error) {
            throw failure(MODEL_STREAM_READ_FAILED);
        }
    }

    private JsonNode streamEvent(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception error) {
            throw failure(MODEL_STREAM_READ_FAILED);
        }
    }

    private String streamContent(JsonNode event) {
        JsonNode delta = event
            .path("choices")
            .path(0)
            .path("delta")
            .path("content");
        return delta.isMissingNode() || delta.isNull() ? null : delta.asText();
    }

    private String endpointPath() {
        String path = settings.target().uri().getRawPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return COMPLETIONS_PATH;
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path + COMPLETIONS_PATH;
    }

    private String requiredApiKey() {
        String apiKey = settings.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw failure(MODEL_NOT_CONFIGURED);
        }
        return apiKey.strip();
    }

    private String requiredModelName() {
        String modelName = settings.model();
        if (modelName == null || modelName.isBlank()) {
            throw failure(MODEL_NOT_CONFIGURED);
        }
        return modelName.strip();
    }

    private Duration requestTimeout() {
        return positiveDuration(settings.requestTimeout(), positiveDuration(defaults.timeout(), Duration.ofSeconds(45)));
    }

    private Duration streamIdleTimeout() {
        return positiveDuration(defaults.streamIdleTimeout(), Duration.ofSeconds(30));
    }

    private int maximumResponseBytes() {
        return defaults.maximumResponseBytes() > 0 ? defaults.maximumResponseBytes() : 1_048_576;
    }

    private String readResponseBody(InputStream input) {
        try (InputStream limited = new ResponseSizeLimitedInputStream(input, maximumResponseBytes())) {
            return new String(limited.readAllBytes(), StandardCharsets.UTF_8);
        } catch (ResponseTooLargeException error) {
            throw failure(MODEL_RESPONSE_TOO_LARGE);
        } catch (IOException error) {
            throw failure(MODEL_RESPONSE_READ_FAILED);
        }
    }

    private Duration positiveDuration(Duration configured, Duration fallback) {
        return configured == null || configured.isZero() || configured.isNegative() ? fallback : configured;
    }

    private boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private ModelClientException failure(ModelErrorCode errorCode) {
        return new ModelClientException(errorCode);
    }

    private ModelClientException failure(ModelErrorCode errorCode, Long consumedTokens) {
        return new ModelClientException(errorCode, consumedTokens);
    }

    private Long reportedTotalTokens(String responseBody) {
        try {
            return reportedTotalTokens(objectMapper.readTree(responseBody));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long reportedTotalTokens(JsonNode payload) {
        JsonNode value = payload.path("usage").path("total_tokens");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        long tokens = value.asLong(-1L);
        return tokens < 0 ? null : tokens;
    }

    private static final class ResponseSizeLimitedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long bytesRead;

        private ResponseSizeLimitedInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                record(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, Math.max(1L, maximumBytes - bytesRead + 1L));
            int count = super.read(buffer, offset, allowed);
            if (count > 0) {
                record(count);
            }
            return count;
        }

        private void record(int count) throws ResponseTooLargeException {
            bytesRead += count;
            if (bytesRead > maximumBytes) {
                throw new ResponseTooLargeException();
            }
        }
    }

    private static final class ResponseTooLargeException extends IOException {
    }
}
