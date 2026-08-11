package com.feng.dsagent.chat;

import com.feng.dsagent.common.ApiException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
class JdbcChatRepository implements ChatRepository, ChatHistoryRepository {

    private static final int MAX_SESSION_RESULTS = 50;
    private static final int MAX_SESSION_MESSAGES = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcChatRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isPublishedChapter(String chapterId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM chapters WHERE id = ? AND status = 'PUBLISHED'",
            Integer.class,
            chapterId
        );
        return count != null && count > 0;
    }

    @Override
    public Optional<List<ChatTurn>> recentHistory(long userId, String sessionId, int limit) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM chat_sessions WHERE id = ? AND user_id = ?",
            Integer.class,
            sessionId,
            userId
        );
        if (count == null || count == 0) {
            return Optional.empty();
        }
        List<ChatTurn> newestFirst = jdbc.query(
            "SELECT role, content FROM chat_messages WHERE session_id = ? ORDER BY id DESC LIMIT ?",
            (row, index) -> new ChatTurn(row.getString("role"), row.getString("content")),
            sessionId,
            Math.max(1, limit)
        );
        List<ChatTurn> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return Optional.of(List.copyOf(chronological));
    }

    @Override
    @Transactional
    public String saveExchange(
        long userId,
        String sessionId,
        String chapterId,
        String prompt,
        String answer,
        List<ChatSource> sources
    ) {
        String resolvedSessionId = sessionId;
        if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
            resolvedSessionId = UUID.randomUUID().toString();
            jdbc.update(
                "INSERT INTO chat_sessions (id, user_id, chapter_id, title) VALUES (?, ?, ?, ?)",
                resolvedSessionId,
                userId,
                blankToNull(chapterId),
                title(prompt)
            );
        } else {
            int updated = jdbc.update(
                "UPDATE chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?",
                resolvedSessionId,
                userId
            );
            if (updated != 1) {
                throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "CHAT_SESSION_NOT_FOUND",
                    "对话会话不存在"
                );
            }
        }
        jdbc.update(
            "INSERT INTO chat_messages (session_id, role, content) VALUES (?, 'user', ?)",
            resolvedSessionId,
            prompt
        );
        jdbc.update(
            "INSERT INTO chat_messages (session_id, role, content, sources_json) VALUES (?, 'assistant', ?, ?)",
            resolvedSessionId,
            answer,
            sourceJson(sources)
        );
        return resolvedSessionId;
    }

    @Override
    public List<ChatSessionSummary> findSessions(long userId) {
        return jdbc.query(
            """
            SELECT s.id, s.chapter_id, s.title, s.updated_at,
                (SELECT COUNT(*) FROM chat_messages m WHERE m.session_id = s.id) AS message_count
            FROM chat_sessions s
            WHERE s.user_id = ?
            ORDER BY s.updated_at DESC, s.id DESC
            LIMIT ?
            """,
            (row, index) -> new ChatSessionSummary(
                row.getString("id"),
                row.getString("chapter_id"),
                row.getString("title"),
                instant(row.getTimestamp("updated_at")),
                row.getLong("message_count")
            ),
            userId,
            MAX_SESSION_RESULTS
        );
    }

    @Override
    public Optional<ChatSessionView> findSession(long userId, String sessionId) {
        List<ChatSessionSummary> sessions = jdbc.query(
            "SELECT id, chapter_id, title, updated_at FROM chat_sessions WHERE id = ? AND user_id = ?",
            (row, index) -> new ChatSessionSummary(
                row.getString("id"),
                row.getString("chapter_id"),
                row.getString("title"),
                instant(row.getTimestamp("updated_at")),
                0
            ),
            sessionId,
            userId
        );
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        ChatSessionSummary session = sessions.getFirst();
        List<ChatMessageView> newestFirst = jdbc.query(
            """
            SELECT id, role, content, sources_json, created_at
            FROM chat_messages
            WHERE session_id = ?
            ORDER BY id DESC
            LIMIT ?
            """,
            (row, index) -> new ChatMessageView(
                row.getLong("id"),
                row.getString("role"),
                row.getString("content"),
                sources(row.getString("sources_json")),
                instant(row.getTimestamp("created_at"))
            ),
            sessionId,
            MAX_SESSION_MESSAGES
        );
        List<ChatMessageView> messages = new ArrayList<>(newestFirst);
        Collections.reverse(messages);
        return Optional.of(new ChatSessionView(
            session.id(), session.chapterId(), session.title(), session.updatedAt(), List.copyOf(messages)
        ));
    }

    @Override
    public boolean deleteSession(long userId, String sessionId) {
        return jdbc.update("DELETE FROM chat_sessions WHERE id = ? AND user_id = ?", sessionId, userId) == 1;
    }

    private List<ChatSource> sources(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            tools.jackson.databind.JsonNode root = objectMapper.readTree(value);
            if (!root.isArray()) {
                return List.of();
            }
            List<ChatSource> sources = new ArrayList<>();
            for (tools.jackson.databind.JsonNode node : root) {
                if (!node.isObject()) {
                    continue;
                }
                sources.add(new ChatSource(
                    node.path("id").asText(),
                    nullableText(node, "chapterId"),
                    node.path("title").asText(),
                    node.path("content").asText(),
                    node.path("source").asText(),
                    nullableText(node, "pageLabel"),
                    node.path("score").asDouble(0),
                    evidenceHash(node)
                ));
            }
            return List.copyOf(sources);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String nullableText(tools.jackson.databind.JsonNode node, String field) {
        return node.path(field).isNull() || node.path(field).isMissingNode() ? null : node.path(field).asText();
    }

    private String evidenceHash(tools.jackson.databind.JsonNode node) {
        String stored = nullableText(node, "evidenceHash");
        if (stored != null && stored.matches("[a-fA-F0-9]{64}")) {
            return stored.toLowerCase(java.util.Locale.ROOT);
        }
        return ChatEvidenceFingerprint.hash(
            node.path("title").asText(),
            node.path("content").asText(),
            node.path("source").asText(),
            nullableText(node, "pageLabel")
        );
    }

    private java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String sourceJson(List<ChatSource> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize chat sources", error);
        }
    }

    private String title(String prompt) {
        String normalized = prompt.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
