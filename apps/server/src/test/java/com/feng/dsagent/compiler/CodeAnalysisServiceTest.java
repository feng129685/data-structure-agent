package com.feng.dsagent.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.learning.LearningEventRepository;
import com.feng.dsagent.learning.LearningEventService;
import com.feng.dsagent.learning.LearningEventView;
import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CodeAnalysisServiceTest {

    @Test
    void asksModelForAConciseTeachingAnalysis() {
        FakeModelClient model = new FakeModelClient();
        model.response = "数组越界发生在 i <= n，应改为 i < n。";
        CodeAnalysisService service = new CodeAnalysisService(properties(), model);

        CodeAnalysisResponse response = service.analyze(new CodeAnalysisRequest(
            "c",
            "for (int i = 0; i <= n; i++) a[i] = 0;",
            "",
            "",
            "index out of bounds",
            "runtime_error",
            "02-linear-list",
            null
        ));

        assertThat(response.analysis()).contains("i < n");
        assertThat(model.request.messages().getFirst().content())
            .contains("不要执行代码")
            .contains("修复建议");
        assertThat(model.request.messages().getLast().content())
            .contains("runtime_error")
            .contains("index out of bounds");
    }

    @Test
    void rejectsOversizedCodeBeforeCallingModel() {
        FakeModelClient model = new FakeModelClient();
        CodeAnalysisService service = new CodeAnalysisService(properties(), model);

        assertThatThrownBy(() -> service.analyze(new CodeAnalysisRequest(
            "python", "x".repeat(101), "", "", "", "success", null, null
        ))).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(error.code()).isEqualTo("COMPILER_CODE_TOO_LONG");
        });
        assertThat(model.request).isNull();
    }

    @Test
    void rejectsUnknownOrOversizedRunStatusBeforeCallingModel() {
        FakeModelClient model = new FakeModelClient();
        CodeAnalysisService service = new CodeAnalysisService(properties(), model);

        assertThatThrownBy(() -> service.analyze(new CodeAnalysisRequest(
            "c", "int main(void) { return 0; }", "", "", "", "success\" injected=\"true", null, null
        ))).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error.code()).isEqualTo("COMPILER_STATUS_INVALID");
        });
        assertThat(model.request).isNull();
    }

    @Test
    void analyzesAnOwnedPersistedRunAndRecordsTrustedLearningEvidence() {
        FakeModelClient model = new FakeModelClient();
        model.response = "真实错误来自数组越界。";
        FakeCodeRuns runs = new FakeCodeRuns(new CodeRunSnapshot(
            "run-1",
            "03-stack-queue",
            "c",
            "int values[1]; values[2] = 3;",
            "",
            "",
            "index out of bounds",
            "runtime_error"
        ));
        FakeLearningEvents events = new FakeLearningEvents();
        CodeAnalysisService service = new CodeAnalysisService(
            properties(),
            model,
            runs,
            new LearningEventService(events, new tools.jackson.databind.ObjectMapper())
        );

        CodeAnalysisResponse response = service.analyze(new CodeAnalysisRequest(
            "python",
            "print('forged')",
            "",
            "forged output",
            "",
            "success",
            "02-linear-list",
            "run-1"
        ), 7L);

        assertThat(response.analysis()).contains("数组越界");
        assertThat(model.request.messages().getLast().content())
            .contains("int values[1]", "index out of bounds", "runtime_error")
            .doesNotContain("forged output", "print('forged')");
        assertThat(events.saved).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("CODE_REVIEW");
            assertThat(event.chapterId()).isEqualTo("03-stack-queue");
            assertThat(event.referenceId()).isEqualTo("run-1");
            assertThat(event.payloadJson()).contains("真实错误来自数组越界");
        });
    }

    private CompilerProperties properties() {
        return new CompilerProperties("http://127.0.0.1:1", Duration.ofSeconds(1), 100, 50, 64, 4, 1, 10_000, 3_000);
    }

    private static final class FakeModelClient implements ModelClient {
        private ModelRequest request;
        private String response;

        @Override
        public ModelResponse complete(ModelRequest request) {
            this.request = request;
            return new ModelResponse(response);
        }

        @Override
        public void stream(ModelRequest request, ModelStreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeCodeRuns implements CodeRunRepository {
        private final CodeRunSnapshot run;

        private FakeCodeRuns(CodeRunSnapshot run) {
            this.run = run;
        }

        @Override
        public String save(long userId, String chapterId, RunCodeRequest request, RunCodeResponse response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CodeRunSnapshot> findOwned(String runId, long userId) {
            return userId == 7L && run.id().equals(runId) ? Optional.of(run) : Optional.empty();
        }
    }

    private static final class FakeLearningEvents implements LearningEventRepository {
        private final List<SavedLearningEvent> saved = new ArrayList<>();

        @Override
        public boolean isPublishedChapter(String chapterId) {
            return "03-stack-queue".equals(chapterId);
        }

        @Override
        public LearningEventView save(
            long userId,
            String eventType,
            String chapterId,
            String referenceId,
            String payloadJson,
            Instant createdAt
        ) {
            saved.add(new SavedLearningEvent(eventType, chapterId, referenceId, payloadJson));
            return new LearningEventView(1L, eventType, chapterId, referenceId, createdAt);
        }
    }

    private record SavedLearningEvent(
        String eventType,
        String chapterId,
        String referenceId,
        String payloadJson
    ) {
    }
}
