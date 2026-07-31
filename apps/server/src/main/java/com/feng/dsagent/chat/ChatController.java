package com.feng.dsagent.chat;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.knowledge.KnowledgeAudience;
import com.feng.dsagent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chat;
    private final ChatHistoryService history;
    private final ChatRateLimiter rateLimiter;

    public ChatController(ChatService chat, ChatHistoryService history, ChatRateLimiter rateLimiter) {
        this.chat = chat;
        this.history = history;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    ChatResponse complete(
        @AuthenticationPrincipal AuthenticatedUser user,
        HttpServletRequest servletRequest,
        @Valid @RequestBody ChatRequest request
    ) {
        Long userId = user == null ? null : user.userId();
        rateLimiter.check(userId, servletRequest.getRemoteAddr());
        return chat.complete(request.command(), userId, KnowledgeAudience.from(user));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
        @AuthenticationPrincipal AuthenticatedUser user,
        HttpServletRequest servletRequest,
        @Valid @RequestBody ChatRequest request
    ) {
        Long userId = user == null ? null : user.userId();
        rateLimiter.check(userId, servletRequest.getRemoteAddr());
        SseEmitter emitter = new SseEmitter(70_000L);
        KnowledgeAudience audience = KnowledgeAudience.from(user);
        Thread.ofVirtual().name("chat-stream-").start(() -> runStream(emitter, request.command(), userId, audience));
        return emitter;
    }

    @GetMapping("/sessions")
    List<ChatSessionSummary> sessions(@AuthenticationPrincipal AuthenticatedUser user) {
        return history.sessions(user.userId());
    }

    @GetMapping("/sessions/{id}")
    ChatSessionView session(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return history.session(user.userId(), id);
    }

    @DeleteMapping("/sessions/{id}")
    ResponseEntity<Void> deleteSession(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        history.delete(user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    private void runStream(
        SseEmitter emitter,
        ChatCommand command,
        Long userId,
        KnowledgeAudience audience
    ) {
        try {
            ChatResponse response = chat.stream(
                command,
                userId,
                audience,
                sources -> send(emitter, "sources", sources),
                content -> send(emitter, "delta", Map.of("content", content))
            );
            send(emitter, "done", response);
            emitter.complete();
        } catch (ApiException error) {
            send(emitter, "error", Map.of("code", error.code(), "message", error.getMessage()));
            emitter.complete();
        } catch (RuntimeException error) {
            send(emitter, "error", Map.of("code", "CHAT_STREAM_FAILED", "message", "流式回答中断"));
            emitter.complete();
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException error) {
            throw new IllegalStateException("Unable to write chat stream", error);
        }
    }

    public record ChatRequest(
        @NotBlank @Size(max = 4000) String prompt,
        @Size(max = 64) String chapterId,
        @Size(max = 64) String sessionId,
        @Size(max = 12) List<@Valid TurnRequest> history
    ) {
        ChatCommand command() {
            List<ChatTurn> turns = history == null ? List.of() : history.stream()
                .map(turn -> new ChatTurn(turn.role(), turn.content()))
                .toList();
            return new ChatCommand(prompt, chapterId, sessionId, turns);
        }
    }

    public record TurnRequest(
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 4000) String content
    ) {
    }
}
