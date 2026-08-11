package com.feng.dsagent.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CompilerServiceTest {

    @Test
    void rejectsUnsupportedLanguagesWithoutCallingTheUpstream() {
        AtomicInteger calls = new AtomicInteger();
        CompilerService service = service(100, 50, 32, (language, code, stdin) -> {
            calls.incrementAndGet();
            return new CompilerExecution("success", "", "");
        });

        assertThatThrownBy(() -> service.run(new RunCodeRequest("java", "class Main {}", "")))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.code()).isEqualTo("COMPILER_LANGUAGE_UNSUPPORTED");
            });
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsCodeAndInputBeyondConfiguredLengths() {
        AtomicInteger calls = new AtomicInteger();
        CompilerService service = service(5, 3, 32, (language, code, stdin) -> {
            calls.incrementAndGet();
            return new CompilerExecution("success", "", "");
        });

        assertApiError(
            () -> service.run(new RunCodeRequest("c", "123456", "")),
            HttpStatus.PAYLOAD_TOO_LARGE,
            "COMPILER_CODE_TOO_LONG"
        );
        assertApiError(
            () -> service.run(new RunCodeRequest("python", "print", "1234")),
            HttpStatus.PAYLOAD_TOO_LARGE,
            "COMPILER_INPUT_TOO_LONG"
        );
        assertThat(calls).hasValue(0);
    }

    @Test
    void truncatesBothOutputStreamsToTheConfiguredMaximum() {
        CompilerService service = service(100, 50, 16, (language, code, stdin) ->
            new CompilerExecution("runtime_error", "abcdefghijklmnopqrstuvwxyz", "0123456789abcdefghijklmnop")
        );

        RunCodeResponse response = service.run(new RunCodeRequest("python", "print('x')", ""));

        assertThat(response.language()).isEqualTo("python");
        assertThat(response.status()).isEqualTo("runtime_error");
        assertThat(response.stdout()).hasSize(16).endsWith("[truncated]");
        assertThat(response.stderr()).hasSize(16).endsWith("[truncated]");
        assertThat(response.durationMs()).isNotNegative();
    }

    @Test
    void persistsCodeRunForAuthenticatedUser() {
        CompilerProperties properties = new CompilerProperties(
            "http://127.0.0.1:1",
            Duration.ofSeconds(1),
            100,
            50,
            64,
            4,
            1,
            10_000,
            3_000
        );
        List<SavedCodeRun> saved = new ArrayList<>();
        CodeRunRepository runs = (userId, chapterId, request, response) -> {
            saved.add(new SavedCodeRun(userId, chapterId, request, response));
            return "run-1";
        };
        CompilerService service = new CompilerService(
            properties,
            (language, code, stdin) -> new CompilerExecution("success", "ok", ""),
            runs
        );

        RunCodeResponse response = service.run(
            new RunCodeRequest("c", "int main(){}", "", "03-stack-queue"),
            7L
        );

        assertThat(saved).singleElement().satisfies(run -> {
            assertThat(run.userId()).isEqualTo(7);
            assertThat(run.chapterId()).isEqualTo("03-stack-queue");
            assertThat(run.response().status()).isEqualTo(response.status());
            assertThat(run.response().runId()).isNull();
        });
        assertThat(response.runId()).isEqualTo("run-1");
    }

    private static CompilerService service(
        int maximumCodeLength,
        int maximumInputLength,
        int maximumOutputLength,
        CompilerGateway gateway
    ) {
        CompilerProperties properties = new CompilerProperties(
            "http://127.0.0.1:1",
            Duration.ofSeconds(1),
            maximumCodeLength,
            maximumInputLength,
            maximumOutputLength,
            4,
            1,
            10_000,
            3_000
        );
        return new CompilerService(properties, gateway);
    }

    private static void assertApiError(Runnable invocation, HttpStatus status, String code) {
        assertThatThrownBy(invocation::run)
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(status);
                assertThat(error.code()).isEqualTo(code);
            });
    }

    private record SavedCodeRun(
        long userId,
        String chapterId,
        RunCodeRequest request,
        RunCodeResponse response
    ) {
    }
}
