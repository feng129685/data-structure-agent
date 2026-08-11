package com.feng.dsagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class LearningEventServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordsAWhitelistedEventForAPublishedChapter() throws Exception {
        FakeLearningEventRepository repository = new FakeLearningEventRepository();
        LearningEventService service = new LearningEventService(repository, objectMapper);

        LearningEventView event = service.record(7L, new LearningEventCommand(
            "resource_view",
            "03-stack-queue",
            "stack-pdf",
            objectMapper.readTree("{\"page\":2}")
        ));

        assertThat(event.eventType()).isEqualTo("RESOURCE_VIEW");
        assertThat(event.chapterId()).isEqualTo("03-stack-queue");
        assertThat(repository.saved).singleElement().satisfies(saved -> {
            assertThat(saved.userId()).isEqualTo(7L);
            assertThat(saved.payloadJson()).contains("page");
        });
    }

    @Test
    void rejectsUnknownEventTypesAndUnpublishedChapters() throws Exception {
        FakeLearningEventRepository repository = new FakeLearningEventRepository();
        LearningEventService service = new LearningEventService(repository, objectMapper);

        assertApiError(
            () -> service.record(7L, new LearningEventCommand("SHELL_EXEC", null, null, null)),
            HttpStatus.BAD_REQUEST,
            "LEARNING_EVENT_TYPE_INVALID"
        );
        assertApiError(
            () -> service.record(7L, new LearningEventCommand("RESOURCE_VIEW", "99-hidden", null, null)),
            HttpStatus.BAD_REQUEST,
            "LEARNING_CHAPTER_INVALID"
        );
    }

    @Test
    void userSubmittedEventsCannotClaimServerManagedActivities() {
        FakeLearningEventRepository repository = new FakeLearningEventRepository();
        LearningEventService service = new LearningEventService(repository, objectMapper);

        assertApiError(
            () -> service.recordUserSubmitted(7L, new LearningEventCommand(
                "CLASSROOM_ANSWER", "03-stack-queue", "session-1", null
            )),
            HttpStatus.BAD_REQUEST,
            "LEARNING_EVENT_SERVER_MANAGED"
        );
        assertThat(repository.saved).isEmpty();
    }

    private static void assertApiError(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    private static final class FakeLearningEventRepository implements LearningEventRepository {
        private final List<SavedEvent> saved = new ArrayList<>();

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
            saved.add(new SavedEvent(userId, eventType, chapterId, referenceId, payloadJson));
            return new LearningEventView(1L, eventType, chapterId, referenceId, createdAt);
        }
    }

    private record SavedEvent(long userId, String eventType, String chapterId, String referenceId, String payloadJson) {
    }
}
