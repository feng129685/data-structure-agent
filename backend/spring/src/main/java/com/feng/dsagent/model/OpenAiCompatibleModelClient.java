package com.feng.dsagent.model;

import static com.feng.dsagent.model.ModelErrorCode.MODEL_EMPTY_RESPONSE;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_NOT_CONFIGURED;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_REQUEST_FAILED;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_REQUEST_TIMEOUT;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_RESPONSE_READ_FAILED;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_RESPONSE_TOO_LARGE;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_STREAM_IDLE_TIMEOUT;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_STREAM_READ_FAILED;
import static com.feng.dsagent.model.ModelErrorCode.MODEL_UPSTREAM_ERROR;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class OpenAiCompatibleModelClient implements ModelClient {

    private static final String COMPLETIONS_PATH = "/chat/completions";

    private final ModelProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleModelClient(ModelProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(requestTimeout())
            .build();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        HttpRequest httpRequest = buildRequest(request, false);
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException error) {
            throw failure(MODEL_REQUEST_TIMEOUT);
        } catch (IOException error) {
            throw failure(MODEL_REQUEST_FAILED);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(MODEL_REQUEST_FAILED);
        }

        if (!isSuccess(response.statusCode())) {
            String responseBody = readResponseBody(response.body());
            throw failure(MODEL_UPSTREAM_ERROR, reportedTotalTokens(responseBody));
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
    }

    @Override
    public void stream(ModelRequest request, ModelStreamHandler handler) {
        Objects.requireNonNull(handler, "handler");
        HttpRequest httpRequest = buildRequest(request, true);
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException error) {
            throw failure(MODEL_REQUEST_TIMEOUT);
        } catch (IOException error) {
            throw failure(MODEL_REQUEST_FAILED);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(MODEL_REQUEST_FAILED);
        }

        if (!isSuccess(response.statusCode())) {
            closeQuietly(response.body());
            throw failure(MODEL_UPSTREAM_ERROR);
        }
        readStream(response.body(), handler);
    }

    private HttpRequest buildRequest(ModelRequest request, boolean stream) {
        Objects.requireNonNull(request, "request");
        String apiKey = requiredApiKey();
        String payload = serialize(request, stream);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
            .timeout(requestTimeout())
            .header("Content-Type", "application/json")
            .header("Accept", stream ? "text/event-stream" : "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (usesApiKeyHeader()) {
            builder.header("api-key", apiKey);
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private boolean usesApiKeyHeader() {
        String provider = properties.provider();
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
        if (request.temperature() != null) {
            payload.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            payload.put("max_tokens", request.maxTokens());
        }
        payload.put("stream", stream);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw failure(MODEL_REQUEST_FAILED);
        }
    }

    private void readStream(InputStream input, ModelStreamHandler handler) {
        StringBuilder receivedContent = new StringBuilder();
        InputStream limitedInput = new ResponseSizeLimitedInputStream(input, maximumResponseBytes());
        try (
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(limitedInput, StandardCharsets.UTF_8));
            limitedInput
        ) {
            while (true) {
                String line = readLine(reader, executor, limitedInput);
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
                String content = streamContent(data);
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
        } catch (IOException error) {
            throw failure(MODEL_STREAM_READ_FAILED);
        }
    }

    private String readLine(BufferedReader reader, ExecutorService executor, InputStream input) {
        Future<String> read = executor.submit(reader::readLine);
        try {
            return read.get(streamIdleTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            closeQuietly(input);
            read.cancel(true);
            throw failure(MODEL_STREAM_IDLE_TIMEOUT);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof ResponseTooLargeException) {
                throw failure(MODEL_RESPONSE_TOO_LARGE);
            }
            throw failure(MODEL_STREAM_READ_FAILED);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(MODEL_STREAM_READ_FAILED);
        }
    }

    private String streamContent(String data) {
        try {
            JsonNode delta = objectMapper.readTree(data)
                .path("choices")
                .path(0)
                .path("delta")
                .path("content");
            return delta.isMissingNode() || delta.isNull() ? null : delta.asText();
        } catch (Exception error) {
            throw failure(MODEL_STREAM_READ_FAILED);
        }
    }

    private URI endpoint() {
        String baseUrl = properties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw failure(MODEL_NOT_CONFIGURED);
        }
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return URI.create(normalized + COMPLETIONS_PATH);
        } catch (IllegalArgumentException error) {
            throw failure(MODEL_NOT_CONFIGURED);
        }
    }

    private String requiredApiKey() {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw failure(MODEL_NOT_CONFIGURED);
        }
        return apiKey.strip();
    }

    private String requiredModelName() {
        String modelName = properties.name();
        if (modelName == null || modelName.isBlank()) {
            throw failure(MODEL_NOT_CONFIGURED);
        }
        return modelName.strip();
    }

    private Duration requestTimeout() {
        return positiveDuration(properties.timeout(), Duration.ofSeconds(45));
    }

    private Duration streamIdleTimeout() {
        return positiveDuration(properties.streamIdleTimeout(), Duration.ofSeconds(30));
    }

    private int maximumResponseBytes() {
        return properties.maximumResponseBytes() > 0 ? properties.maximumResponseBytes() : 1_048_576;
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

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The stable upstream error remains more useful than a close failure.
        }
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
