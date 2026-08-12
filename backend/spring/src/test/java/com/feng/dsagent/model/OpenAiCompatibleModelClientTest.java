package com.feng.dsagent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleModelClientTest {

    private static final String API_KEY = "test-api-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completesUsingOpenAiRequestShapeAndBearerAuthenticationOnly() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        URI baseUrl = startServer("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "application/json", """
                {"choices":[{"message":{"content":"A stack is LIFO."}}],"usage":{"total_tokens":37}}
                """);
        }).resolve("/v1/");
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));
        ModelRequest request = new ModelRequest(
            List.of(
                new ModelMessage("system", "Teach data structures."),
                new ModelMessage("user", "Explain stacks.")
            ),
            0.2,
            512
        );

        ModelResponse response = client.complete(request);

        assertThat(response.content()).isEqualTo("A stack is LIFO.");
        assertThat(response.totalTokens()).isEqualTo(37L);
        assertThat(authorization.get()).isEqualTo("Bearer " + API_KEY);
        assertThat(apiKey.get()).isNull();
        assertThat(requestBody.get().path("model").asText()).isEqualTo("test-model");
        assertThat(requestBody.get().path("stream").asBoolean()).isFalse();
        assertThat(requestBody.get().path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(requestBody.get().path("max_tokens").asInt()).isEqualTo(512);
        assertThat(requestBody.get().path("messages").path(1).path("role").asText()).isEqualTo("user");
        assertThat(requestBody.get().path("messages").path(1).path("content").asText())
            .isEqualTo("Explain stacks.");
    }

    @Test
    void usesApiKeyHeaderOnlyForAzureProvider() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        URI baseUrl = startServer("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            respond(exchange, 200, "application/json", """
                {"choices":[{"message":{"content":"ok"}}]}
                """);
        });
        ModelClient client = client("azure-openai", baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));

        client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello"))));

        assertThat(authorization.get()).isNull();
        assertThat(apiKey.get()).isEqualTo(API_KEY);
    }

    @Test
    void streamsSseContentUntilDoneAndIgnoresMetadataOnlyEvents() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        URI baseUrl = startServer("/chat/completions", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "text/event-stream", """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"push"}}]}

                data: {"choices":[{"delta":{"content":" then pop"}}]}

                data: [DONE]

                """);
        });
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));
        List<String> fragments = new ArrayList<>();

        client.stream(new ModelRequest(List.of(new ModelMessage("user", "Show stack operations."))), fragments::add);

        assertThat(fragments).containsExactly("push", " then pop");
        assertThat(requestBody.get().path("stream").asBoolean()).isTrue();
        assertThat(requestBody.get().path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void streamsProviderUsageFromTheFinalSseChunkBeforeDone() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 200, "text/event-stream", """
                data: {"choices":[{"delta":{"content":"push"}}]}

                data: {"choices":[],"usage":{"total_tokens":41}}

                data: [DONE]

                """)
        );
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));
        List<String> fragments = new ArrayList<>();
        List<Long> usages = new ArrayList<>();

        client.stream(
            new ModelRequest(List.of(new ModelMessage("user", "Show stack operations."))),
            new ModelStreamHandler() {
                @Override
                public void onContent(String content) {
                    fragments.add(content);
                }

                @Override
                public void onUsage(Long totalTokens) {
                    usages.add(totalTokens);
                }
            }
        );

        assertThat(fragments).containsExactly("push");
        assertThat(usages).containsExactly(41L);
    }

    @Test
    void reportsProviderUsageFromANonSuccessStreamingResponseWithoutExposingTheBody() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 429, "application/json", """
                {"usage":{"total_tokens":29},"error":{"message":"private provider diagnostic"}}
                """)
        );
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));

        ModelClientException error = catchThrowableOfType(
            () -> client.stream(new ModelRequest(List.of(new ModelMessage("user", "Show stack operations."))), ignored -> { }),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_UPSTREAM_ERROR");
        assertThat(error.consumedTokens()).isEqualTo(29L);
        assertThat(error.getMessage()).doesNotContain("private provider diagnostic", API_KEY);
    }

    @Test
    void rejectsMissingApiKeyWithoutContactingUpstream() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        URI baseUrl = startServer("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            respond(exchange, 200, "application/json", "{}");
        });
        ModelClient client = client(baseUrl, "  ", Duration.ofSeconds(2), Duration.ofSeconds(2));

        ModelClientException completeError = catchThrowableOfType(
            () -> client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello")))),
            ModelClientException.class
        );
        ModelClientException streamError = catchThrowableOfType(
            () -> client.stream(new ModelRequest(List.of(new ModelMessage("user", "hello"))), ignored -> { }),
            ModelClientException.class
        );

        assertThat(completeError.code()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(streamError.code()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void mapsRequestTimeoutToStableErrorCode() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange -> {
            sleep(Duration.ofMillis(500));
            respond(exchange, 200, "application/json", """
                {"choices":[{"message":{"content":"late"}}]}
                """);
        });
        ModelClient client = client(baseUrl, API_KEY, Duration.ofMillis(80), Duration.ofSeconds(2));

        ModelClientException error = catchThrowableOfType(
            () -> client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello")))),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_REQUEST_TIMEOUT");
    }

    @Test
    void mapsNonSuccessResponseWithoutLeakingApiKeyOrUpstreamBody() throws Exception {
        String upstreamBody = "private-upstream-detail";
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 429, "application/json", upstreamBody + " " + API_KEY)
        );
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));

        ModelClientException error = catchThrowableOfType(
            () -> client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello")))),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_UPSTREAM_ERROR");
        assertThat(error.getMessage()).doesNotContain(API_KEY, upstreamBody);
        assertThat(error.getCause()).isNull();
    }

    @Test
    void rejectsEmptyCompletionContent() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 200, "application/json", """
                {"choices":[{"message":{"content":"   "}}]}
                """)
        );
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));

        ModelClientException error = catchThrowableOfType(
            () -> client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello")))),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_EMPTY_RESPONSE");
    }

    @Test
    void mapsEmptyHttpBodyToEmptyResponse() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 200, "application/json", "")
        );
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));

        ModelClientException error = catchThrowableOfType(
            () -> client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello")))),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_EMPTY_RESPONSE");
    }

    @Test
    void mapsMalformedCompletionToReadFailureWithoutLeakingResponseData() throws Exception {
        String malformedResponse = "private-malformed-response";
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 200, "application/json", "{" + malformedResponse)
        );
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));

        ModelClientException error = catchThrowableOfType(
            () -> client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello")))),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_RESPONSE_READ_FAILED");
        assertThat(error.getMessage()).doesNotContain(API_KEY, malformedResponse);
        assertThat(error.getCause()).isNull();
    }

    @Test
    void mapsStreamIdleTimeoutToStableErrorCode() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            sleep(Duration.ofSeconds(2));
            exchange.close();
        });
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofMillis(80));

        long startedAt = System.nanoTime();
        ModelClientException error = catchThrowableOfType(
            () -> client.stream(new ModelRequest(List.of(new ModelMessage("user", "hello"))), ignored -> { }),
            ModelClientException.class
        );
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(error.code()).isEqualTo("MODEL_STREAM_IDLE_TIMEOUT");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void mapsMalformedStreamEventToReadFailureWithoutLeakingEventData() throws Exception {
        String malformedEvent = "private-malformed-event";
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 200, "text/event-stream", "data: {" + malformedEvent + "}\n\n")
        );
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));

        ModelClientException error = catchThrowableOfType(
            () -> client.stream(new ModelRequest(List.of(new ModelMessage("user", "hello"))), ignored -> { }),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_STREAM_READ_FAILED");
        assertThat(error.getMessage()).doesNotContain(API_KEY, malformedEvent);
        assertThat(error.getCause()).isNull();
    }

    @Test
    void rejectsStreamThatFinishesWithoutContent() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 200, "text/event-stream", "data: [DONE]\n\n")
        );
        ModelClient client = client(baseUrl, API_KEY, Duration.ofSeconds(2), Duration.ofSeconds(2));

        ModelClientException error = catchThrowableOfType(
            () -> client.stream(new ModelRequest(List.of(new ModelMessage("user", "hello"))), ignored -> { }),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_EMPTY_RESPONSE");
    }

    @Test
    void rejectsCompletionBodiesBeyondTheConfiguredByteLimit() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 200, "application/json", "x".repeat(256))
        );
        ModelClient client = client(
            "openai-compatible",
            baseUrl,
            API_KEY,
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            64
        );

        ModelClientException error = catchThrowableOfType(
            () -> client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello")))),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_RESPONSE_TOO_LARGE");
    }

    @Test
    void rejectsStreamsBeyondTheConfiguredByteLimit() throws Exception {
        URI baseUrl = startServer("/chat/completions", exchange ->
            respond(exchange, 200, "text/event-stream", "data: " + "x".repeat(256) + "\n\n")
        );
        ModelClient client = client(
            "openai-compatible",
            baseUrl,
            API_KEY,
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            64
        );

        ModelClientException error = catchThrowableOfType(
            () -> client.stream(new ModelRequest(List.of(new ModelMessage("user", "hello"))), ignored -> { }),
            ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_RESPONSE_TOO_LARGE");
    }

    private ModelClient client(URI baseUrl, String apiKey, Duration timeout, Duration streamIdleTimeout) {
        return client("openai-compatible", baseUrl, apiKey, timeout, streamIdleTimeout);
    }

    private ModelClient client(
        String provider,
        URI baseUrl,
        String apiKey,
        Duration timeout,
        Duration streamIdleTimeout
    ) {
        return client(provider, baseUrl, apiKey, timeout, streamIdleTimeout, 1_048_576);
    }

    private ModelClient client(
        String provider,
        URI baseUrl,
        String apiKey,
        Duration timeout,
        Duration streamIdleTimeout,
        int maximumResponseBytes
    ) {
        ModelProperties properties = new ModelProperties(
            provider,
            apiKey,
            baseUrl.toString(),
            "test-model",
            timeout,
            streamIdleTimeout,
            maximumResponseBytes
        );
        return new OpenAiCompatibleModelClient(properties, objectMapper);
    }

    private URI startServer(String path, HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, handler);
        server.start();
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
