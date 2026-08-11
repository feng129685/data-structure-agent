package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PinnedHttpsTransportTest {

    @Test
    void connectsOnlyToTheValidatedAddressWhileRetainingTheOriginalHost() throws Exception {
        InetAddress address = ip(1, 1, 1, 1);
        RecordingConnectionFactory connections = new RecordingConnectionFactory("""
            HTTP/1.1 204 No Content\r
            Content-Length: 0\r
            \r
            """);
        PinnedHttpsTransport transport = new PinnedHttpsTransport(connections);
        ModelConfigResolvedTarget target = new ModelConfigResolvedTarget(
            URI.create("https://model.example/v1"),
            "model.example",
            443,
            List.of(address)
        );

        try (PinnedHttpsResponse response = transport.execute(
            target,
            new PinnedHttpsRequest("GET", "/v1", Map.of("Accept", "application/json"), new byte[0]),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)
        )) {
            assertThat(response.statusCode()).isEqualTo(204);
        }

        assertThat(connections.address).isEqualTo(address);
        assertThat(connections.port).isEqualTo(443);
        assertThat(connections.host).isEqualTo("model.example");
        assertThat(connections.request()).contains("GET /v1 HTTP/1.1\r\n");
        assertThat(connections.request()).contains("Host: model.example\r\n");
        assertThat(connections.request()).contains("Accept: application/json\r\n");
    }

    @Test
    void rejectsAnUnsafeHeaderValueBeforeOpeningAConnection() {
        RecordingConnectionFactory connections = new RecordingConnectionFactory("HTTP/1.1 204 No Content\r\n\r\n");
        PinnedHttpsTransport transport = new PinnedHttpsTransport(connections);
        ModelConfigResolvedTarget target = new ModelConfigResolvedTarget(
            URI.create("https://model.example/v1"),
            "model.example",
            443,
            List.of(ip(1, 1, 1, 1))
        );

        assertThatThrownBy(() -> transport.execute(
            target,
            new PinnedHttpsRequest("GET", "/v1", Map.of("Authorization", "Bearer good\r\nbad"), new byte[0]),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)
        )).isInstanceOf(java.io.IOException.class);

        assertThat(connections.opened).isZero();
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

    private static final class RecordingConnectionFactory implements PinnedHttpsConnectionFactory {
        private final String response;
        private int opened;
        private InetAddress address;
        private int port;
        private String host;
        private ByteArrayOutputStream output;

        private RecordingConnectionFactory(String response) {
            this.response = response;
        }

        @Override
        public PinnedHttpsConnection connect(InetAddress address, int port, String host, Duration timeout) {
            this.opened++;
            this.address = address;
            this.port = port;
            this.host = host;
            this.output = new ByteArrayOutputStream();
            return new PinnedHttpsConnection() {
                @Override
                public InputStream input() {
                    return new ByteArrayInputStream(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
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
            return output.toString(java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
}
