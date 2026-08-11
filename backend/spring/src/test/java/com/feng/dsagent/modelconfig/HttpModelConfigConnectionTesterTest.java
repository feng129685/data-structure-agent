package com.feng.dsagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpModelConfigConnectionTesterTest {

    @Test
    void postsAnOpenAiCompatibleCompletionToTheResolvedAddressWithoutResolvingTheOriginalHostnameAgain()
        throws Exception {
        InetAddress address = ip(1, 1, 1, 1);
        String body = "{\"choices\":[{\"message\":{\"content\":\"connected\"}}]}";
        RecordingConnections connections = new RecordingConnections(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + "\r\n\r\n"
                + body
        );
        HttpModelConfigConnectionTester tester = new HttpModelConfigConnectionTester(new PinnedHttpsTransport(connections));
        ModelConfigResolvedTarget target = new ModelConfigResolvedTarget(
            URI.create("https://model.example/v1"),
            "model.example",
            443,
            List.of(address)
        );

        ModelConfigConnectionResult result = tester.test(new ModelConfigConnection(
            "custom",
            target,
            "model-a",
            "opaque-key"
        ));

        assertThat(result.connected()).isTrue();
        assertThat(result.code()).isEqualTo("CONNECTION_OK");
        assertThat(connections.address).isEqualTo(address);
        assertThat(connections.host).isEqualTo("model.example");
        assertThat(connections.request()).contains("POST /v1/chat/completions HTTP/1.1\r\n");
        assertThat(connections.request()).contains("Authorization: Bearer opaque-key\r\n");
        assertThat(connections.request()).contains("\"model\":\"model-a\"");
    }

    @Test
    void rejectsASuccessfulHttpStatusWithoutAnOpenAiCompatibleCompletion() throws Exception {
        RecordingConnections connections = new RecordingConnections(
            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
        );
        HttpModelConfigConnectionTester tester = new HttpModelConfigConnectionTester(new PinnedHttpsTransport(connections));

        ModelConfigConnectionResult result = tester.test(connection());

        assertThat(result.connected()).isFalse();
        assertThat(result.code()).isEqualTo("CONNECTION_RESPONSE_INVALID");
        assertThat(connections.request()).contains("POST /v1/chat/completions HTTP/1.1\r\n");
    }

    @Test
    void rejectsRedirectsWithoutFollowingTheLocation() throws Exception {
        RecordingConnections connections = new RecordingConnections(
            "HTTP/1.1 302 Found\r\nLocation: https://other.example/v1\r\nContent-Length: 0\r\n\r\n"
        );
        HttpModelConfigConnectionTester tester = new HttpModelConfigConnectionTester(new PinnedHttpsTransport(connections));

        ModelConfigConnectionResult result = tester.test(connection());

        assertThat(result.connected()).isFalse();
        assertThat(result.code()).isEqualTo("REDIRECT_REJECTED");
        assertThat(connections.request()).contains("POST /v1/chat/completions HTTP/1.1\r\n");
    }

    private ModelConfigConnection connection() throws Exception {
        return new ModelConfigConnection(
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
        private InetAddress address;
        private String host;
        private ByteArrayOutputStream output;

        private RecordingConnections(String response) {
            this.response = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public PinnedHttpsConnection connect(InetAddress address, int port, String host, Duration timeout) {
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
            return output.toString(java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
}
