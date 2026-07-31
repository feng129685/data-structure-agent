package com.feng.dsagent.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PistonCompilerGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsCToTheFixedGccRuntimeAndForwardsStandardInput() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        startServer(exchange -> {
            capturedRequest.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                {"language":"c","version":"14.1.0","run":{"stdout":"42\\n","stderr":"","code":0,"signal":null}}
                """);
        });

        PistonCompilerGateway gateway = new PistonCompilerGateway(properties(Duration.ofSeconds(2)), objectMapper);
        CompilerExecution execution = gateway.execute(SupportedLanguage.C, "int main(void) { return 0; }", "42\n");

        assertThat(capturedRequest.get().get("language").asText()).isEqualTo("gcc");
        assertThat(capturedRequest.get().get("version").asText()).isEqualTo("*");
        assertThat(capturedRequest.get().get("stdin").asText()).isEqualTo("42\n");
        assertThat(capturedRequest.get().get("compile_timeout").asInt()).isGreaterThan(0);
        assertThat(capturedRequest.get().get("run_timeout").asInt()).isGreaterThan(0);
        assertThat(capturedRequest.get().get("files").get(0).get("name").asText()).isEqualTo("main.c");
        assertThat(execution.status()).isEqualTo("success");
        assertThat(execution.stdout()).isEqualTo("42\n");
    }

    @Test
    void mapsPythonToTheFixedPythonRuntime() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        startServer(exchange -> {
            capturedRequest.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                {"language":"python","version":"3.12.0","run":{"stdout":"ok","stderr":"","code":0}}
                """);
        });

        PistonCompilerGateway gateway = new PistonCompilerGateway(properties(Duration.ofSeconds(2)), objectMapper);
        gateway.execute(SupportedLanguage.PYTHON, "print('ok')", "");

        assertThat(capturedRequest.get().get("language").asText()).isEqualTo("python");
        assertThat(capturedRequest.get().get("files").get(0).get("name").asText()).isEqualTo("main.py");
    }

    @Test
    void convertsUpstreamTimeoutToAStableApiError() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(400);
                respond(exchange, 200, "{\"run\":{\"stdout\":\"late\",\"stderr\":\"\",\"code\":0}}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        PistonCompilerGateway gateway = new PistonCompilerGateway(properties(Duration.ofMillis(80)), objectMapper);

        assertThatThrownBy(() -> gateway.execute(SupportedLanguage.PYTHON, "print('late')", ""))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                assertThat(error.code()).isEqualTo("COMPILER_UPSTREAM_TIMEOUT");
                assertThat(error.getMessage()).doesNotContain("127.0.0.1");
            });
    }

    @Test
    void hidesUpstreamFailureDetailsBehindAStableApiError() throws Exception {
        startServer(exchange -> respond(exchange, 503, "secret upstream diagnostics"));
        PistonCompilerGateway gateway = new PistonCompilerGateway(properties(Duration.ofSeconds(2)), objectMapper);

        assertThatThrownBy(() -> gateway.execute(SupportedLanguage.C, "int main(void) {}", ""))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(error.code()).isEqualTo("COMPILER_UPSTREAM_UNAVAILABLE");
                assertThat(error.getMessage()).doesNotContain("secret");
            });
    }

    @Test
    void rejectsOversizedInputBeforeSendingAnythingToPiston() throws Exception {
        AtomicInteger upstreamCalls = new AtomicInteger();
        startServer(exchange -> {
            upstreamCalls.incrementAndGet();
            respond(exchange, 200, "{\"run\":{\"stdout\":\"\",\"stderr\":\"\",\"code\":0}}");
        });
        CompilerProperties properties = properties(Duration.ofSeconds(2), 5, 3, 32);
        CompilerService service = new CompilerService(
            properties,
            new PistonCompilerGateway(properties, objectMapper)
        );

        assertThatThrownBy(() -> service.run(new RunCodeRequest("c", "123456", "")))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code()).isEqualTo("COMPILER_CODE_TOO_LONG")
            );
        assertThatThrownBy(() -> service.run(new RunCodeRequest("python", "print", "1234")))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code()).isEqualTo("COMPILER_INPUT_TOO_LONG")
            );
        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    void truncatesLongOutputReceivedFromPiston() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
            {"run":{"stdout":"abcdefghijklmnopqrstuvwxyz","stderr":"0123456789abcdefghijklmnop","code":1}}
            """));
        CompilerProperties properties = properties(Duration.ofSeconds(2), 100, 50, 16);
        CompilerService service = new CompilerService(
            properties,
            new PistonCompilerGateway(properties, objectMapper)
        );

        RunCodeResponse response = service.run(new RunCodeRequest("python", "print('x')", ""));

        assertThat(response.status()).isEqualTo("runtime_error");
        assertThat(response.stdout()).hasSize(16).endsWith("[truncated]");
        assertThat(response.stderr()).hasSize(16).endsWith("[truncated]");
    }

    @Test
    void returnsCompileErrorsWhenPistonOmitsTheRunPhase() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
            {"compile":{"stdout":"","stderr":"main.c:1: error: expected ';'","code":1,"signal":null}}
            """));
        PistonCompilerGateway gateway = new PistonCompilerGateway(properties(Duration.ofSeconds(2)), objectMapper);

        CompilerExecution execution = gateway.execute(SupportedLanguage.C, "broken code", "");

        assertThat(execution.status()).isEqualTo("compile_error");
        assertThat(execution.stdout()).isEmpty();
        assertThat(execution.stderr()).contains("expected ';'");
    }

    private void startServer(ThrowingHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/execute", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception error) {
                exchange.close();
            }
        });
        server.start();
    }

    private CompilerProperties properties(Duration timeout) {
        return properties(timeout, 100, 50, 32);
    }

    private CompilerProperties properties(
        Duration timeout,
        int maximumCodeLength,
        int maximumInputLength,
        int maximumOutputLength
    ) {
        return new CompilerProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            timeout,
            maximumCodeLength,
            maximumInputLength,
            maximumOutputLength,
            4,
            1,
            10_000,
            3_000
        );
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
