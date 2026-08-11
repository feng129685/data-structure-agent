package com.feng.dsagent.compiler;

import com.feng.dsagent.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class PistonCompilerGateway implements CompilerGateway {

    private static final int MINIMUM_RESPONSE_LIMIT = 65_536;
    private static final int MAXIMUM_RESPONSE_LIMIT = 10_000_000;

    private final CompilerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    PistonCompilerGateway(CompilerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public CompilerExecution execute(SupportedLanguage language, String code, String stdin) {
        if (!properties.configured()) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "COMPILER_NOT_CONFIGURED",
                "代码执行服务尚未配置"
            );
        }
        String requestBody = serialize(new PistonRequest(
            language.pistonRuntime(),
            "*",
            stdin,
            List.of(new PistonFile(language.fileName(), code)),
            properties.compileTimeoutMillis(),
            properties.runTimeoutMillis()
        ));
        HttpRequest request = HttpRequest.newBuilder(properties.executeUri())
            .timeout(properties.timeout())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw upstreamUnavailable();
                }
                return parse(readLimited(body));
            }
        } catch (ApiException error) {
            throw error;
        } catch (HttpTimeoutException error) {
            throw new ApiException(
                HttpStatus.GATEWAY_TIMEOUT,
                "COMPILER_UPSTREAM_TIMEOUT",
                "代码执行服务响应超时，请稍后重试"
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "COMPILER_EXECUTION_INTERRUPTED",
                "代码执行请求已中断，请重试"
            );
        } catch (IOException error) {
            throw upstreamUnavailable();
        }
    }

    private String serialize(PistonRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encode compiler request", error);
        }
    }

    private String readLimited(InputStream body) throws IOException {
        long configuredLimit = (long) properties.maximumOutputLength() * 4L + 16_384L;
        int limit = (int) Math.min(
            MAXIMUM_RESPONSE_LIMIT,
            Math.max(MINIMUM_RESPONSE_LIMIT, configuredLimit)
        );
        byte[] bytes = body.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw invalidResponse();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private CompilerExecution parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode compile = root.path("compile");
            boolean compileFailed = compile.isObject() && exitCode(compile) != 0;
            if (compileFailed) {
                return new CompilerExecution(
                    "compile_error",
                    output(compile, "stdout"),
                    output(compile, "stderr")
                );
            }

            JsonNode run = root.path("run");
            if (!run.isObject()) {
                throw invalidResponse();
            }
            boolean runFailed = exitCode(run) != 0 || hasText(run.path("signal"));
            String status = runFailed ? "runtime_error" : "success";

            String stdout = output(run, "stdout");
            String compileError = compile.isObject() ? output(compile, "stderr") : "";
            String runError = output(run, "stderr");
            String stderr = joinOutput(compileError, runError);
            return new CompilerExecution(status, stdout, stderr);
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw invalidResponse();
        }
    }

    private static int exitCode(JsonNode phase) {
        JsonNode code = phase.path("code");
        return code.isNumber() ? code.asInt() : -1;
    }

    private static boolean hasText(JsonNode value) {
        return !value.isMissingNode() && !value.isNull() && !value.asText().isBlank();
    }

    private static String output(JsonNode phase, String field) {
        JsonNode value = phase.path(field);
        if (value.isTextual()) {
            return value.asText();
        }
        JsonNode combined = phase.path("output");
        return combined.isTextual() ? combined.asText() : "";
    }

    private static String joinOutput(String first, String second) {
        if (first.isBlank()) {
            return second;
        }
        if (second.isBlank()) {
            return first;
        }
        return first + System.lineSeparator() + second;
    }

    private static ApiException upstreamUnavailable() {
        return new ApiException(
            HttpStatus.BAD_GATEWAY,
            "COMPILER_UPSTREAM_UNAVAILABLE",
            "代码执行服务暂时不可用，请稍后重试"
        );
    }

    private static ApiException invalidResponse() {
        return new ApiException(
            HttpStatus.BAD_GATEWAY,
            "COMPILER_UPSTREAM_INVALID_RESPONSE",
            "代码执行服务返回了无效结果，请稍后重试"
        );
    }

    private record PistonRequest(
        String language,
        String version,
        String stdin,
        List<PistonFile> files,
        int compile_timeout,
        int run_timeout
    ) {
    }

    private record PistonFile(String name, String content) {
    }
}
