package com.feng.dsagent.compiler;

import com.feng.dsagent.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class CompilerService {

    private static final String TRUNCATION_MARKER = "[truncated]";

    private final CompilerProperties properties;
    private final CompilerGateway gateway;
    private final CodeRunRepository runs;

    CompilerService(CompilerProperties properties, CompilerGateway gateway) {
        this(properties, gateway, null);
    }

    @Autowired
    CompilerService(CompilerProperties properties, CompilerGateway gateway, CodeRunRepository runs) {
        this.properties = properties;
        this.gateway = gateway;
        this.runs = runs;
    }

    public RunCodeResponse run(RunCodeRequest request) {
        return run(request, null);
    }

    public RunCodeResponse run(RunCodeRequest request, Long userId) {
        if (request == null) {
            throw badRequest("COMPILER_REQUEST_INVALID", "请输入代码执行参数");
        }
        SupportedLanguage language = SupportedLanguage.fromApiName(request.language())
            .orElseThrow(() -> badRequest("COMPILER_LANGUAGE_UNSUPPORTED", "仅支持 C 和 Python"));
        String code = request.code();
        String stdin = request.stdin() == null ? "" : request.stdin();

        if (code == null || code.isBlank()) {
            throw badRequest("COMPILER_CODE_REQUIRED", "代码不能为空");
        }
        if (code.length() > properties.maximumCodeLength()) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "COMPILER_CODE_TOO_LONG",
                "代码长度超过允许上限"
            );
        }
        if (stdin.length() > properties.maximumInputLength()) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "COMPILER_INPUT_TOO_LONG",
                "标准输入长度超过允许上限"
            );
        }
        String chapterId = normalizeChapterId(request.chapterId());
        if (userId != null && chapterId != null && runs != null && !runs.chapterExists(chapterId)) {
            throw badRequest("COMPILER_CHAPTER_INVALID", "章节不存在或尚未发布");
        }

        long startedAt = System.nanoTime();
        CompilerExecution execution = gateway.execute(language, code, stdin);
        long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        RunCodeResponse response = new RunCodeResponse(
            language.apiName(),
            execution.status(),
            truncate(execution.stdout()),
            truncate(execution.stderr()),
            durationMs,
            null
        );
        if (userId != null && runs != null) {
            String runId = runs.save(userId, chapterId, request, response);
            return new RunCodeResponse(
                response.language(),
                response.status(),
                response.stdout(),
                response.stderr(),
                response.durationMs(),
                runId
            );
        }
        return response;
    }

    private String normalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return null;
        }
        String normalized = chapterId.trim();
        if (normalized.length() > 64 || !normalized.matches("^[0-9]{2}-[a-z0-9-]+$")) {
            throw badRequest("COMPILER_CHAPTER_INVALID", "章节参数无效");
        }
        return normalized;
    }

    private String truncate(String value) {
        String output = value == null ? "" : value;
        int maximumLength = properties.maximumOutputLength();
        if (output.length() <= maximumLength) {
            return output;
        }
        if (maximumLength <= TRUNCATION_MARKER.length()) {
            return TRUNCATION_MARKER.substring(0, maximumLength);
        }
        return output.substring(0, maximumLength - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
