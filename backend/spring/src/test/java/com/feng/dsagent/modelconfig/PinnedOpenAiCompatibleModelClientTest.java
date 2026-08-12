package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.feng.dsagent.model.ModelMessage;
import com.feng.dsagent.model.ModelProperties;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PinnedOpenAiCompatibleModelClientTest {

    @Test
    void completesThroughThePersistedResolvedTargetWithoutHostnameResolution() {
        InetAddress address = ip(1, 1, 1, 1);
        RecordingConnections connections = new RecordingConnections("""
            {"choices":[{"message":{"content":"database"}}],"usage":{"total_tokens":29}}
            """);
        ModelConfigRuntimeSettings settings = new ModelConfigRuntimeSettings(
            "custom",
            new ModelConfigResolvedTarget(
                URI.create("https://model.example/v1"),
                "model.example",
                443,
                List.of(address)
            ),
            "model-a",
            "opaque-key"
        );
        PinnedOpenAiCompatibleModelClient client = new PinnedOpenAiCompatibleModelClient(
            settings,
            defaults(),
            new ObjectMapper(),
            new PinnedHttpsTransport(connections)
        );

        ModelResponse response = client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello"))));

        assertThat(response.content()).isEqualTo("database");
        assertThat(response.totalTokens()).isEqualTo(29L);
        assertThat(connections.address).isEqualTo(address);
        assertThat(connections.host).isEqualTo("model.example");
        assertThat(connections.request()).contains("POST /v1/chat/completions HTTP/1.1\r\n");
        assertThat(connections.request()).contains("Authorization: Bearer opaque-key\r\n");
        assertThat(connections.request()).contains("\"model\":\"model-a\"");
    }

    @Test
    void appliesPersistedTemperatureAndOutputLimitWhileCappingPerRequestOverrides() {
        RecordingConnections connections = new RecordingConnections("""
            {"choices":[{"message":{"content":"database"}}]}
            """);
        ModelConfigRuntimeSettings settings = new ModelConfigRuntimeSettings(
            "custom",
            new ModelConfigResolvedTarget(
                URI.create("https://model.example/v1"),
                "model.example",
                443,
                List.of(ip(1, 1, 1, 1))
            ),
            "model-a",
            "opaque-key",
            0.35,
            640,
            Duration.ofSeconds(12),
            0,
            4096
        );
        PinnedOpenAiCompatibleModelClient client = new PinnedOpenAiCompatibleModelClient(
            settings,
            defaults(),
            new ObjectMapper(),
            new PinnedHttpsTransport(connections)
        );

        client.complete(new ModelRequest(
            List.of(new ModelMessage("user", "hello")),
            0.9,
            1000
        ));

        assertThat(connections.request()).contains("\"temperature\":0.9");
        assertThat(connections.request()).contains("\"max_tokens\":640");
    }

    @Test
    void retriesAConnectionFailureAccordingToThePersistedRetryCount() {
        RecordingConnections connections = new RecordingConnections("""
            {"choices":[{"message":{"content":"retried"}}]}
            """, 1);
        ModelConfigRuntimeSettings settings = new ModelConfigRuntimeSettings(
            "custom",
            new ModelConfigResolvedTarget(
                URI.create("https://model.example/v1"),
                "model.example",
                443,
                List.of(ip(1, 1, 1, 1))
            ),
            "model-a",
            "opaque-key",
            0.2,
            640,
            Duration.ofSeconds(12),
            1,
            4096
        );
        PinnedOpenAiCompatibleModelClient client = new PinnedOpenAiCompatibleModelClient(
            settings,
            defaults(),
            new ObjectMapper(),
            new PinnedHttpsTransport(connections)
        );

        assertThat(client.complete(new ModelRequest(List.of(new ModelMessage("user", "hello")))).content())
            .isEqualTo("retried");
        assertThat(connections.connectCalls).isEqualTo(2);
    }

    @Test
    void streamsProviderUsageFromTheFinalSseChunkBeforeDone() {
        RecordingConnections connections = new RecordingConnections("""
            data: {"choices":[{"delta":{"content":"stack"}}]}

            data: {"choices":[],"usage":{"total_tokens":53}}

            data: [DONE]
            """);
        PinnedOpenAiCompatibleModelClient client = new PinnedOpenAiCompatibleModelClient(
            settings(),
            defaults(),
            new ObjectMapper(),
            new PinnedHttpsTransport(connections)
        );
        List<String> fragments = new ArrayList<>();
        List<Long> usages = new ArrayList<>();

        client.stream(
            new ModelRequest(List.of(new ModelMessage("user", "hello"))),
            new com.feng.dsagent.model.ModelStreamHandler() {
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

        assertThat(fragments).containsExactly("stack");
        assertThat(usages).containsExactly(53L);
        assertThat(connections.request()).contains("\"stream_options\":{\"include_usage\":true}");
    }

    @Test
    void reportsProviderUsageFromANonSuccessStreamingResponseWithoutExposingTheBody() {
        PinnedOpenAiCompatibleModelClient client = new PinnedOpenAiCompatibleModelClient(
            settings(),
            defaults(),
            new ObjectMapper(),
            new PinnedHttpsTransport(new RecordingConnections(429, """
                {"usage":{"total_tokens":31},"error":{"message":"private provider diagnostic"}}
                """))
        );

        com.feng.dsagent.model.ModelClientException error = catchThrowableOfType(
            () -> client.stream(new ModelRequest(List.of(new ModelMessage("user", "hello"))), ignored -> { }),
            com.feng.dsagent.model.ModelClientException.class
        );

        assertThat(error.code()).isEqualTo("MODEL_UPSTREAM_ERROR");
        assertThat(error.consumedTokens()).isEqualTo(31L);
        assertThat(error.getMessage()).doesNotContain("private provider diagnostic", "opaque-key");
    }

    private ModelProperties defaults() {
        return new ModelProperties(
            "environment",
            "environment-key",
            "https://environment.example/v1",
            "environment-model",
            Duration.ofSeconds(2),
            Duration.ofSeconds(1),
            1_048_576
        );
    }

    private ModelConfigRuntimeSettings settings() {
        return new ModelConfigRuntimeSettings(
            "custom",
            new ModelConfigResolvedTarget(
                URI.create("https://model.example/v1"),
                "model.example",
                443,
                List.of(ip(1, 1, 1, 1))
            ),
            "model-a",
            "opaque-key"
        );
    }

    private InetAddress ip(int first, int second, int third, int fourth) {
        try {
            return InetAddress.getByAddress(new byte[] {
                (byte) first,
                (byte) second,
                (byte) third,
                (byte) fourth
            });
        } catch (java.net.UnknownHostException error) {
            throw new AssertionError(error);
        }
    }

    private static final class RecordingConnections implements PinnedHttpsConnectionFactory {
        private final byte[] response;
        private int failuresBeforeSuccess;
        private int connectCalls;
        private InetAddress address;
        private String host;
        private ByteArrayOutputStream output;

        private RecordingConnections(String body) {
            this(200, body, 0);
        }

        private RecordingConnections(String body, int failuresBeforeSuccess) {
            this(200, body, failuresBeforeSuccess);
        }

        private RecordingConnections(int status, String body) {
            this(status, body, 0);
        }

        private RecordingConnections(int status, String body, int failuresBeforeSuccess) {
            byte[] bodyBytes = body.strip().getBytes(StandardCharsets.UTF_8);
            byte[] headerBytes = ("HTTP/1.1 " + status + " response\r\nContent-Length: " + bodyBytes.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
            this.response = java.util.Arrays.copyOf(headerBytes, headerBytes.length + bodyBytes.length);
            System.arraycopy(bodyBytes, 0, this.response, headerBytes.length, bodyBytes.length);
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public PinnedHttpsConnection connect(InetAddress address, int port, String host, Duration timeout)
            throws java.io.IOException {
            connectCalls++;
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--;
                throw new java.io.IOException("simulated connection failure");
            }
            this.address = address;
            this.host = host;
            this.output = new ByteArrayOutputStream();
            return new PinnedHttpsConnection() {
                @Override
                public InputStream input() {
                    return new ByteArrayInputStream(response);
                }

                @Override
                public OutputStream output() {
                    return output;
                }

                @Override
                public void setReadTimeout(Duration timeout) {
                }

                @Override
                public void close() {
                }
            };
        }

        private String request() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
