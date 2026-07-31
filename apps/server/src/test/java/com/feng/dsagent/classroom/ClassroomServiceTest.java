package com.feng.dsagent.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.learning.LearningEventRepository;
import com.feng.dsagent.learning.LearningEventService;
import com.feng.dsagent.learning.LearningEventView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class ClassroomServiceTest {

    private final FakeClassroomRepository repository = new FakeClassroomRepository();
    private final FakeLearningEventRepository learningEvents = new FakeLearningEventRepository();
    private final ClassroomService service = new ClassroomService(
        repository,
        new ClassroomStateMachine(),
        new ClassroomScriptParser(new ObjectMapper()),
        new ClassroomAnswerEvaluator(),
        new LearningEventService(learningEvents, new ObjectMapper())
    );

    @Test
    void createsSessionFromPublishedScriptAtOpeningStage() {
        repository.scripts.put("stack-script", script());

        ClassroomSessionView session = service.create(7, "stack-script");

        assertThat(session.userId()).isEqualTo(7);
        assertThat(session.state()).isEqualTo(ClassroomState.OPENING);
        assertThat(session.paused()).isFalse();
        assertThat(session.stage().path("content").asText()).isEqualTo("欢迎进入栈与队列课堂");
        assertThat(repository.sessions).containsKey(session.id());
    }

    @Test
    void recordsStudentAnswerAndMovesWaitingSessionToDiscussion() {
        repository.scripts.put("stack-script", script());
        ClassroomSessionView created = service.create(7, "stack-script");
        repository.sessions.put(created.id(), new ClassroomSessionRecord(
            created.id(), 7, "stack-script", "03-stack-queue", ClassroomState.WAITING, false, null,
            script().scriptJson()
        ));

        ClassroomSessionView updated = service.apply(
            7,
            created.id(),
            ClassroomAction.ANSWER,
            "栈是后进先出"
        );

        assertThat(updated.state()).isEqualTo(ClassroomState.DISCUSS);
        assertThat(updated.answerEvaluation().status()).isEqualTo(ClassroomAnswerStatus.INCORRECT);
        assertThat(repository.events).singleElement().satisfies(event -> {
            assertThat(event.content()).isEqualTo("栈是后进先出");
            assertThat(event.fromState()).isEqualTo(ClassroomState.WAITING);
            assertThat(event.toState()).isEqualTo(ClassroomState.DISCUSS);
            assertThat(event.answerEvaluation().status()).isEqualTo(ClassroomAnswerStatus.INCORRECT);
        });
        assertThat(learningEvents.saved).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("CLASSROOM_ANSWER");
            assertThat(event.chapterId()).isEqualTo("03-stack-queue");
            assertThat(event.referenceId()).isEqualTo(created.id());
            assertThat(event.payloadJson()).contains("INCORRECT", "栈是后进先出");
        });
    }

    @Test
    void correctAnswerSkipsMisconceptionDiscussionAndMovesToBlackboard() {
        repository.scripts.put("stack-script", stepScript());
        ClassroomSessionView created = service.create(7, "stack-script");
        repository.sessions.put(created.id(), new ClassroomSessionRecord(
            created.id(), 7, "stack-script", "03-stack-queue", ClassroomState.WAITING, false, null,
            stepScript().scriptJson()
        ));

        ClassroomSessionView updated = service.apply(7, created.id(), ClassroomAction.ANSWER, "C");

        assertThat(updated.state()).isEqualTo(ClassroomState.BLACKBOARD);
        assertThat(updated.answerEvaluation().status()).isEqualTo(ClassroomAnswerStatus.CORRECT);
    }

    @Test
    void hidesSessionsOwnedByAnotherUser() {
        repository.scripts.put("stack-script", script());
        ClassroomSessionView created = service.create(7, "stack-script");

        assertThatThrownBy(() -> service.get(8, created.id()))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(error.code()).isEqualTo("CLASSROOM_SESSION_NOT_FOUND");
            });
    }

    @Test
    void mapsIllegalStateTransitionToConflictResponse() {
        repository.scripts.put("stack-script", script());
        ClassroomSessionView created = service.create(7, "stack-script");

        assertThatThrownBy(() -> service.apply(7, created.id(), ClassroomAction.ANSWER, "too early"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.code()).isEqualTo("CLASSROOM_ACTION_INVALID");
            });
    }

    private ClassroomScript script() {
        return new ClassroomScript(
            "stack-script",
            "03-stack-queue",
            "栈与队列互动课堂",
            "1.0",
            """
                {
                  "stages": {
                    "OPENING": {"speaker":"teacher","content":"欢迎进入栈与队列课堂"},
                    "DISCUSS": {"speaker":"classmate","content":"我们来比较这份答案"}
                  }
                }
                """
        );
    }

    private ClassroomScript stepScript() {
        return new ClassroomScript(
            "stack-script",
            "03-stack-queue",
            "栈与队列互动课堂",
            "2.0",
            """
                {
                  "lessonId":"03-stack-queue-01",
                  "title":"栈的定义与基本操作",
                  "objectives":["理解后进先出"],
                  "steps":[
                    {"type":"explain","role":"teacher","contentRef":"slide-03","animationRef":"stack-push"},
                    {
                      "type":"question",
                      "role":"teacher",
                      "prompt":"A、B、C 依次入栈后首先出栈的是谁？",
                      "expected":["C"],
                      "misconceptions":["A","B"]
                    }
                  ]
                }
                """
        );
    }

    private static final class FakeClassroomRepository implements ClassroomRepository {
        private final Map<String, ClassroomScript> scripts = new LinkedHashMap<>();
        private final Map<String, ClassroomSessionRecord> sessions = new LinkedHashMap<>();
        private final List<ClassroomEventRecord> events = new ArrayList<>();

        @Override
        public List<ClassroomScript> findPublishedScripts(String chapterId) {
            return scripts.values().stream()
                .filter(script -> chapterId == null || chapterId.equals(script.chapterId()))
                .toList();
        }

        @Override
        public Optional<ClassroomScript> findPublishedScript(String id) {
            return Optional.ofNullable(scripts.get(id));
        }

        @Override
        public ClassroomSessionRecord createSession(long userId, ClassroomScript script, ClassroomStatus status) {
            String id = "session-" + (sessions.size() + 1);
            ClassroomSessionRecord session = new ClassroomSessionRecord(
                id, userId, script.id(), script.chapterId(), status.state(), status.paused(), null,
                script.scriptJson()
            );
            sessions.put(id, session);
            return session;
        }

        @Override
        public Optional<ClassroomSessionRecord> findSession(String id, long userId) {
            ClassroomSessionRecord session = sessions.get(id);
            return session != null && session.userId() == userId ? Optional.of(session) : Optional.empty();
        }

        @Override
        public ClassroomSessionRecord updateSession(
            ClassroomSessionRecord session,
            ClassroomStatus status,
            String summary
        ) {
            ClassroomSessionRecord updated = new ClassroomSessionRecord(
                session.id(), session.userId(), session.scriptId(), session.chapterId(), status.state(),
                status.paused(), summary,
                session.scriptJson()
            );
            sessions.put(session.id(), updated);
            return updated;
        }

        @Override
        public void appendEvent(ClassroomEventRecord event) {
            events.add(event);
        }
    }

    private static final class FakeLearningEventRepository implements LearningEventRepository {
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
