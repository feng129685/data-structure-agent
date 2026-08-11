package com.feng.dsagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatHistoryServiceTest {

    @Test
    void listsGetsAndDeletesOnlyTheAuthenticatedUsersSessions() {
        FakeHistoryRepository repository = new FakeHistoryRepository();
        ChatSessionView owned = new ChatSessionView(
            "session-1",
            "03-stack-queue",
            "解释栈的入栈操作",
            Instant.parse("2026-07-22T10:00:00Z"),
            List.of(new ChatMessageView(
                1L,
                "user",
                "什么是入栈？",
                List.of(),
                Instant.parse("2026-07-22T09:59:00Z")
            ))
        );
        repository.add(7L, owned);
        repository.add(8L, new ChatSessionView(
            "session-other", null, "别人的会话", Instant.parse("2026-07-22T11:00:00Z"), List.of()
        ));
        ChatHistoryService service = new ChatHistoryService(repository);

        assertThat(service.sessions(7L)).extracting(ChatSessionSummary::id).containsExactly("session-1");
        assertThat(service.session(7L, "session-1").messages()).singleElement()
            .extracting(ChatMessageView::content).isEqualTo("什么是入栈？");
        assertThat(service.delete(7L, "session-1")).isTrue();
        assertThat(service.sessions(7L)).isEmpty();
        assertThat(repository.sessionsFor(8L)).containsKey("session-other");
    }

    @Test
    void treatsAnotherUsersSessionAsNotFound() {
        FakeHistoryRepository repository = new FakeHistoryRepository();
        repository.add(8L, new ChatSessionView(
            "session-other", null, "别人的会话", Instant.parse("2026-07-22T11:00:00Z"), List.of()
        ));
        ChatHistoryService service = new ChatHistoryService(repository);

        assertThatThrownBy(() -> service.session(7L, "session-other"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(error.code()).isEqualTo("CHAT_SESSION_NOT_FOUND");
            });
        assertThatThrownBy(() -> service.delete(7L, "session-other"))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code()).isEqualTo("CHAT_SESSION_NOT_FOUND")
            );
    }

    private static final class FakeHistoryRepository implements ChatHistoryRepository {
        private final Map<Long, Map<String, ChatSessionView>> sessions = new LinkedHashMap<>();

        void add(long userId, ChatSessionView session) {
            sessionsFor(userId).put(session.id(), session);
        }

        Map<String, ChatSessionView> sessionsFor(long userId) {
            return sessions.computeIfAbsent(userId, ignored -> new LinkedHashMap<>());
        }

        @Override
        public List<ChatSessionSummary> findSessions(long userId) {
            return sessionsFor(userId).values().stream()
                .map(view -> new ChatSessionSummary(
                    view.id(), view.chapterId(), view.title(), view.updatedAt(), view.messages().size()
                ))
                .toList();
        }

        @Override
        public Optional<ChatSessionView> findSession(long userId, String sessionId) {
            return Optional.ofNullable(sessionsFor(userId).get(sessionId));
        }

        @Override
        public boolean deleteSession(long userId, String sessionId) {
            return sessionsFor(userId).remove(sessionId) != null;
        }
    }
}
