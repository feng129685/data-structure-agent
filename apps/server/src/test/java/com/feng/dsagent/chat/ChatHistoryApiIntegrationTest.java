package com.feng.dsagent.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.JwtTokenService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ChatHistoryApiIntegrationTest {

    private static final long OWNER = 8501L;
    private static final long OTHER = 8502L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private ChatRepository chats;

    @BeforeEach
    void prepareData() {
        jdbc.update("DELETE FROM chat_messages WHERE session_id LIKE 'history-api-%'");
        jdbc.update("DELETE FROM chat_sessions WHERE id LIKE 'history-api-%'");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (?, ?)", OWNER, OTHER);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", OWNER, OTHER);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)", OWNER, "owner-history@example.com", "hash");
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)", OTHER, "other-history@example.com", "hash");
        jdbc.update(
            "INSERT INTO chat_sessions (id, user_id, chapter_id, title) VALUES (?, ?, ?, ?)",
            "history-api-owned", OWNER, "03-stack-queue", "栈的操作"
        );
        jdbc.update(
            "INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)",
            "history-api-owned", "user", "什么是入栈？"
        );
    }

    @Test
    void exposesAndDeletesOnlyOwnedChatSessions() throws Exception {
        String ownerToken = token(OWNER, "owner-history@example.com");
        String otherToken = token(OTHER, "other-history@example.com");

        mockMvc.perform(get("/api/v1/chat/sessions").header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("history-api-owned"));

        mockMvc.perform(get("/api/v1/chat/sessions/history-api-owned").header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[0].content").value("什么是入栈？"));

        mockMvc.perform(get("/api/v1/chat/sessions/history-api-owned").header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/chat/sessions/history-api-owned").header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isNoContent());
    }

    @Test
    void returnsOnlyTheFiftyMostRecentlyUpdatedSessions() throws Exception {
        Instant base = Instant.parse("2030-01-01T00:00:00Z");
        for (int index = 0; index < 55; index++) {
            jdbc.update(
                "INSERT INTO chat_sessions (id, user_id, title, updated_at) VALUES (?, ?, ?, ?)",
                "history-api-list-%02d".formatted(index),
                OWNER,
                "会话 " + index,
                Timestamp.from(base.plusSeconds(index))
            );
        }

        mockMvc.perform(get("/api/v1/chat/sessions").header("Authorization", "Bearer " + token(OWNER, "owner-history@example.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(50))
            .andExpect(jsonPath("$[0].id").value("history-api-list-54"))
            .andExpect(jsonPath("$[49].id").value("history-api-list-05"));
    }

    @Test
    void returnsOnlyTheLatestTwoHundredMessagesInChronologicalOrder() throws Exception {
        for (int index = 1; index <= 205; index++) {
            jdbc.update(
                "INSERT INTO chat_messages (session_id, role, content) VALUES (?, 'user', ?)",
                "history-api-owned",
                "消息-%03d".formatted(index)
            );
        }

        mockMvc.perform(get("/api/v1/chat/sessions/history-api-owned")
                .header("Authorization", "Bearer " + token(OWNER, "owner-history@example.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages.length()").value(200))
            .andExpect(jsonPath("$.messages[0].content").value("消息-006"))
            .andExpect(jsonPath("$.messages[199].content").value("消息-205"));
    }

    @Test
    void rejectsWritingToADeletedOrForeignSessionBeforeMessagesAreInserted() {
        jdbc.update(
            "INSERT INTO chat_sessions (id, user_id, title) VALUES (?, ?, ?)",
            "history-api-foreign", OTHER, "其他用户的会话"
        );

        assertThatThrownBy(() -> chats.saveExchange(
            OWNER,
            "history-api-foreign",
            null,
            "越权问题",
            "不应保存的回答",
            List.of()
        )).isInstanceOfSatisfying(ApiException.class, error -> {
            org.assertj.core.api.Assertions.assertThat(error.status().value()).isEqualTo(404);
            org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("CHAT_SESSION_NOT_FOUND");
        });

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
            Integer.class,
            "history-api-foreign"
        )).isZero();
    }

    private String token(long userId, String email) {
        return tokens.issue(userId, email, Set.of("STUDENT"));
    }
}
