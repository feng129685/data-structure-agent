package com.feng.dsagent.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.learning.LearningEventRepository;
import com.feng.dsagent.learning.LearningEventService;
import com.feng.dsagent.learning.LearningEventView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class AnimationObservationServiceTest {

    @Test
    void storesAnOwnedObservationAndAddsItToLearningProgress() {
        FakeAnimationObservationRepository animations = new FakeAnimationObservationRepository();
        animations.records.add(new AnimationRecord("animation-1", "03-stack-queue"));
        FakeLearningEvents events = new FakeLearningEvents();
        AnimationObservationService service = new AnimationObservationService(
            animations,
            new LearningEventService(events, new ObjectMapper())
        );

        AnimationObservationView view = service.record(7L, "animation-1", "后进先出，3 最后入栈却最先出栈。");

        assertThat(view.recordId()).isEqualTo("animation-1");
        assertThat(view.observation()).contains("后进先出");
        assertThat(animations.updated).containsExactly("animation-1:后进先出，3 最后入栈却最先出栈。");
        assertThat(events.saved).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("ANIMATION_OBSERVATION");
            assertThat(event.chapterId()).isEqualTo("03-stack-queue");
            assertThat(event.payloadJson()).contains("后进先出");
        });
    }

    @Test
    void doesNotRevealAnimationsOwnedByAnotherUser() {
        AnimationObservationService service = new AnimationObservationService(
            new FakeAnimationObservationRepository(),
            new LearningEventService(new FakeLearningEvents(), new ObjectMapper())
        );

        assertThatThrownBy(() -> service.record(7L, "other-user-record", "观察"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(error.code()).isEqualTo("ANIMATION_RECORD_NOT_FOUND");
            });
    }

    private static final class FakeAnimationObservationRepository implements AnimationObservationRepository {
        private final List<AnimationRecord> records = new ArrayList<>();
        private final List<String> updated = new ArrayList<>();

        @Override
        public Optional<AnimationRecord> findOwned(long userId, String recordId) {
            return records.stream().filter(record -> record.id().equals(recordId)).findFirst();
        }

        @Override
        public void appendObservation(long userId, String recordId, String observation) {
            updated.add(recordId + ":" + observation);
        }
    }

    private static final class FakeLearningEvents implements LearningEventRepository {
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

    private record SavedEvent(
        long userId,
        String eventType,
        String chapterId,
        String referenceId,
        String payloadJson
    ) {
    }
}
