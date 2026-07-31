package com.feng.dsagent.animation;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.learning.LearningEventCommand;
import com.feng.dsagent.learning.LearningEventService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnimationObservationService {

    private final AnimationObservationRepository repository;
    private final LearningEventService learningEvents;

    AnimationObservationService(AnimationObservationRepository repository, LearningEventService learningEvents) {
        this.repository = repository;
        this.learningEvents = learningEvents;
    }

    @Transactional
    public AnimationObservationView record(long userId, String recordId, String observation) {
        AnimationRecord animation = repository.findOwned(userId, normalizeRecordId(recordId))
            .orElseThrow(this::notFound);
        String normalizedObservation = normalizeObservation(observation);
        repository.appendObservation(userId, animation.id(), normalizedObservation);
        tools.jackson.databind.node.ObjectNode payload = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        payload.put("observation", normalizedObservation);
        learningEvents.record(userId, new LearningEventCommand(
            "ANIMATION_OBSERVATION",
            animation.chapterId(),
            animation.id(),
            payload
        ));
        return new AnimationObservationView(animation.id(), normalizedObservation);
    }

    private String normalizeRecordId(String recordId) {
        if (recordId == null || recordId.isBlank() || recordId.length() > 64) {
            throw notFound();
        }
        return recordId.trim();
    }

    private String normalizeObservation(String observation) {
        String normalized = observation == null ? "" : observation.trim();
        if (normalized.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANIMATION_OBSERVATION_REQUIRED", "请填写动画观察结论");
        }
        if (normalized.length() > 2_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANIMATION_OBSERVATION_TOO_LONG", "动画观察内容过长");
        }
        return normalized;
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ANIMATION_RECORD_NOT_FOUND", "动画记录不存在");
    }
}
