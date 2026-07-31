package com.feng.dsagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.knowledge.KnowledgeChunk;
import com.feng.dsagent.knowledge.KnowledgeAudience;
import com.feng.dsagent.knowledge.KnowledgeProperties;
import com.feng.dsagent.knowledge.KnowledgeSearchService;
import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelMessage;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatServiceTest {

    private final CapturingModelClient model = new CapturingModelClient();
    private final FakeChatRepository repository = new FakeChatRepository();
    private final KnowledgeSearchService knowledge = new KnowledgeSearchService(List.of(
        new KnowledgeChunk(
            "stack-1",
            "03-stack-queue",
            "栈的定义和操作",
            "栈是后进先出的线性结构，入栈和出栈都在栈顶进行。",
            "textbook/lessons/03-01-栈.md",
            "第 52-58 页",
            "PUBLIC"
        ),
        new KnowledgeChunk(
            "stack-team",
            "03-stack-queue",
            "教师内部栈底稿",
            "栈的教师内部讲解顺序尚未公开，不能提供给学生或游客。",
            "team/03-stack.md",
            null,
            "TEAM_ONLY"
        )
    ), 4);
    private final ChatService service = new ChatService(
        model,
        knowledge,
        repository,
        new KnowledgeProperties(true, true, "unused", 4, 4, 1100)
    );

    @Test
    void guestChatUsesReviewedKnowledgeButDoesNotPersist() {
        model.response = "栈遵循后进先出。";

        ChatResponse response = service.complete(
            new ChatCommand("什么是栈？", "03-stack-queue", null, List.of()),
            null,
            KnowledgeAudience.GUEST
        );

        assertThat(response.answer()).isEqualTo("栈遵循后进先出。");
        assertThat(response.sessionId()).isNull();
        assertThat(response.sources()).singleElement().satisfies(source -> {
            assertThat(source.title()).isEqualTo("栈的定义和操作");
            assertThat(source.pageLabel()).isEqualTo("第 52-58 页");
            assertThat(source.content()).contains("后进先出");
        });
        assertThat(repository.saved).isEmpty();
        assertThat(model.lastRequest.messages().getFirst().content())
            .contains("经过审核的课程资料")
            .contains("动画演示");
        assertThat(model.lastRequest.messages())
            .anySatisfy(message -> assertThat(message.content()).contains("栈是后进先出"));
        assertThat(model.lastRequest.messages())
            .noneSatisfy(message -> assertThat(message.content()).contains("教师内部讲解顺序"));
    }

    @Test
    void authenticatedChatLoadsOwnedHistoryAndPersistsExchange() {
        model.response = "继续回答。";
        repository.history.put("7:session-1", List.of(
            new ChatTurn("user", "上一问"),
            new ChatTurn("assistant", "上一答")
        ));

        ChatResponse response = service.complete(
            new ChatCommand("继续解释", "03-stack-queue", "session-1", List.of()),
            7L,
            KnowledgeAudience.STUDENT
        );

        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(repository.saved).singleElement().satisfies(saved -> {
            assertThat(saved.userId()).isEqualTo(7);
            assertThat(saved.prompt()).isEqualTo("继续解释");
            assertThat(saved.answer()).isEqualTo("继续回答。");
        });
        assertThat(model.lastRequest.messages())
            .extracting(ModelMessage::content)
            .containsSubsequence("上一问", "上一答", "继续解释");
    }

    @Test
    void rejectsSessionOwnedByAnotherUserWithoutCallingModel() {
        assertThatThrownBy(() -> service.complete(
            new ChatCommand("继续", null, "missing-session", List.of()),
            7L,
            KnowledgeAudience.STUDENT
        )).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(error.code()).isEqualTo("CHAT_SESSION_NOT_FOUND");
        });

        assertThat(model.lastRequest).isNull();
    }

    @Test
    void rejectsUnknownChapterBeforeCallingModel() {
        repository.chapterExists = false;

        assertThatThrownBy(() -> service.complete(
            new ChatCommand("解释这个章节", "99-missing", null, List.of()),
            7L,
            KnowledgeAudience.STUDENT
        )).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error.code()).isEqualTo("CHAT_CHAPTER_INVALID");
        });

        assertThat(model.lastRequest).isNull();
        assertThat(repository.saved).isEmpty();
    }

    @Test
    void streamingForAuthenticatedUserPersistsTheCombinedAnswer() {
        model.streamFragments = List.of("先入", "后出");
        List<String> fragments = new ArrayList<>();

        ChatResponse response = service.stream(
            new ChatCommand("演示栈", "03-stack-queue", null, List.of()),
            7L,
            KnowledgeAudience.STUDENT,
            ignored -> { },
            fragments::add
        );

        assertThat(fragments).containsExactly("先入", "后出");
        assertThat(response.answer()).isEqualTo("先入后出");
        assertThat(repository.saved).singleElement().satisfies(saved -> {
            assertThat(saved.answer()).isEqualTo("先入后出");
            assertThat(saved.sessionId()).isEqualTo(response.sessionId());
        });
    }

    private static final class CapturingModelClient implements ModelClient {
        private ModelRequest lastRequest;
        private String response;
        private List<String> streamFragments = List.of();

        @Override
        public ModelResponse complete(ModelRequest request) {
            lastRequest = request;
            return new ModelResponse(response);
        }

        @Override
        public void stream(ModelRequest request, ModelStreamHandler handler) {
            lastRequest = request;
            streamFragments.forEach(handler::onContent);
        }
    }

    private static final class FakeChatRepository implements ChatRepository {
        private final Map<String, List<ChatTurn>> history = new LinkedHashMap<>();
        private final List<SavedExchange> saved = new ArrayList<>();
        private boolean chapterExists = true;

        @Override
        public boolean isPublishedChapter(String chapterId) {
            return chapterExists;
        }

        @Override
        public Optional<List<ChatTurn>> recentHistory(long userId, String sessionId, int limit) {
            return Optional.ofNullable(history.get(userId + ":" + sessionId));
        }

        @Override
        public String saveExchange(
            long userId,
            String sessionId,
            String chapterId,
            String prompt,
            String answer,
            List<ChatSource> sources
        ) {
            String resolved = sessionId == null ? "session-" + (saved.size() + 1) : sessionId;
            saved.add(new SavedExchange(userId, resolved, prompt, answer));
            return resolved;
        }
    }

    private record SavedExchange(long userId, String sessionId, String prompt, String answer) {
    }
}
