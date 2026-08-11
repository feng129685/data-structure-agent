package com.feng.dsagent.learning;

import com.feng.dsagent.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class LearningEventService {

    private static final Set<String> EVENT_TYPES = Set.of(
        "RESOURCE_VIEW",
        "RESOURCE_DOWNLOAD",
        "ANIMATION_SIMULATION",
        "ANIMATION_OBSERVATION",
        "CLASSROOM_ANSWER",
        "CODE_REVIEW",
        "REVIEW_COMPLETED",
        "WEAKNESS_RECORDED"
    );
    private static final Set<String> SERVER_MANAGED_EVENT_TYPES = Set.of(
        "ANIMATION_OBSERVATION",
        "ANIMATION_SIMULATION",
        "CLASSROOM_ANSWER",
        "CODE_REVIEW"
    );
    private static final int MAX_PAYLOAD_LENGTH = 8_000;

    private final LearningEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LearningEventService(LearningEventRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, Clock.systemUTC());
    }

    @Autowired
    LearningEventService(LearningEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public LearningEventView record(long userId, LearningEventCommand command) {
        if (command == null) {
            throw invalidEvent();
        }
        String eventType = normalizeEventType(command.eventType());
        String chapterId = normalizeChapterId(command.chapterId());
        String referenceId = normalizeReferenceId(command.referenceId());
        String payloadJson = serialize(command.payload());
        Instant createdAt = clock.instant();
        return repository.save(userId, eventType, chapterId, referenceId, payloadJson, createdAt);
    }

    public LearningEventView recordUserSubmitted(long userId, LearningEventCommand command) {
        if (command == null) {
            throw invalidEvent();
        }
        String eventType = normalizeEventType(command.eventType());
        if (SERVER_MANAGED_EVENT_TYPES.contains(eventType)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "LEARNING_EVENT_SERVER_MANAGED",
                "该学习记录只能由对应学习流程生成"
            );
        }
        return record(userId, new LearningEventCommand(
            eventType,
            command.chapterId(),
            command.referenceId(),
            command.payload()
        ));
    }

    private String normalizeEventType(String eventType) {
        String normalized = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        if (!EVENT_TYPES.contains(normalized)) {
            throw invalidEvent();
        }
        return normalized;
    }

    private String normalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return null;
        }
        String normalized = chapterId.trim();
        if (normalized.length() > 64 || !normalized.matches("^[0-9]{2}-[a-z0-9-]+$")
                || !repository.isPublishedChapter(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEARNING_CHAPTER_INVALID", "章节不存在或尚未发布");
        }
        return normalized;
    }

    private String normalizeReferenceId(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            return null;
        }
        String normalized = referenceId.trim();
        if (normalized.length() > 96) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEARNING_REFERENCE_INVALID", "学习记录关联编号无效");
        }
        return normalized;
    }

    private String serialize(tools.jackson.databind.JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > MAX_PAYLOAD_LENGTH) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "LEARNING_PAYLOAD_TOO_LONG", "学习记录内容过长");
            }
            return json;
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEARNING_PAYLOAD_INVALID", "学习记录内容无效");
        }
    }

    private ApiException invalidEvent() {
        return new ApiException(HttpStatus.BAD_REQUEST, "LEARNING_EVENT_TYPE_INVALID", "学习记录类型无效");
    }
}
