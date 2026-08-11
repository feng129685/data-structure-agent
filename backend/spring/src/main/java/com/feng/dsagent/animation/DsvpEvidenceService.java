package com.feng.dsagent.animation;

import com.feng.dsagent.learning.LearningEventCommand;
import com.feng.dsagent.learning.LearningEventService;
import com.feng.dsagent.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Persists server-owned DSVP evidence after a request has passed validation. */
@Service
public class DsvpEvidenceService {

    public static final String SIMULATION_EVENT_TYPE = "ANIMATION_SIMULATION";
    private static final String REVIEW_STATUS = "UNREVIEWED";

    private final DsvpAnimationAdapter adapter;
    private final AnimationRepository animations;
    private final JdbcTemplate jdbc;
    private final LearningEventService learningEvents;
    private final ObjectMapper objectMapper;
    private final DsvpEvidenceResolver resolver;

    public DsvpEvidenceService(
        DsvpAnimationAdapter adapter,
        AnimationRepository animations,
        JdbcTemplate jdbc,
        LearningEventService learningEvents,
        ObjectMapper objectMapper,
        DsvpEvidenceResolver resolver
    ) {
        this.adapter = adapter;
        this.animations = animations;
        this.jdbc = jdbc;
        this.learningEvents = learningEvents;
        this.objectMapper = objectMapper;
        this.resolver = resolver;
    }

    /**
     * Adapts and records a DSVP request as one atomic server-owned evidence unit.
     * The source enum keeps the channel classification outside the client payload.
     */
    @Transactional
    public DsvpSimulationResponse simulate(long userId, JsonNode request, DsvpEvidenceSource source) {
        return simulate(userId, Set.of("STUDENT"), request, source);
    }

    @Transactional
    public DsvpSimulationResponse simulate(
        long userId,
        Set<String> roles,
        JsonNode request,
        DsvpEvidenceSource source
    ) {
        DsvpSimulationResponse response = adapter.adapt(request);
        DsvpEvidenceResolution resolution = resolver.resolve(
            userId,
            roles == null ? Set.of("STUDENT") : Set.copyOf(roles),
            response.request(),
            source
        );
        if (!resolution.persistable()) {
            return preview(response);
        }
        lockEvidenceOwner(userId);
        String requestJson = response.request().toString();
        String traceId = evidenceTraceId(userId, requestJson);
        response = withTraceId(response, traceId);
        String requestHash = sha256(requestJson);
        String sourceRef = resolution.sourceRef();

        Optional<String> existing = existingRecord(
            userId,
            traceId,
            requestHash,
            source,
            sourceRef,
            resolution.chapterId()
        );
        if (existing.isPresent()) {
            return persisted(response, existing.get(), resolution);
        }

        String recordId = persistAnimationRecord(userId, resolution.chapterId(), response);

        persistSnapshot(
            traceId,
            recordId,
            response.protocol(),
            requestJson,
            requestHash,
            source.type(),
            sourceRef,
            response.request().path("version").asText("1.0")
        );
        learningEvents.record(userId, new LearningEventCommand(
            SIMULATION_EVENT_TYPE,
            resolution.chapterId(),
            traceId,
            learningPayload(
                response,
                traceId,
                recordId,
                requestHash,
                source,
                sourceRef,
                resolution.chapterId(),
                resolution.matchSource()
            )
        ));
        return persisted(response, recordId, resolution);
    }

    private String persistAnimationRecord(
        long userId,
        String chapterId,
        DsvpSimulationResponse response
    ) {
        try {
            return animations.save(userId, chapterId, response.animationData(), objectMapper.writeValueAsString(response.animationData()));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to persist DSVP animation record", error);
        }
    }

    private void lockEvidenceOwner(long userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
    }

    private synchronized void persistSnapshot(
        String traceId,
        String animationRecordId,
        String protocol,
        String requestJson,
        String requestHash,
        String sourceType,
        String sourceRef,
        String versionLabel
    ) {
        String existingHash = jdbc.query(
            "SELECT request_hash FROM dsvp_request_snapshots WHERE id = ?",
            (row, index) -> row.getString("request_hash"),
            traceId
        ).stream().findFirst().orElse(null);
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw new IllegalStateException("DSVP trace id collision for " + traceId);
            }
            return;
        }
        jdbc.update(
            """
            INSERT INTO dsvp_request_snapshots (
                id, animation_record_id, protocol_version, request_json, request_hash,
                source_type, source_ref, version_label, review_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            traceId,
            animationRecordId,
            protocol,
            requestJson,
            requestHash,
            sourceType,
            sourceRef,
            versionLabel,
            REVIEW_STATUS
        );
    }

    private ObjectNode learningPayload(
        DsvpSimulationResponse response,
        String traceId,
        String recordId,
        String requestHash,
        DsvpEvidenceSource source,
        String sourceRef,
        String chapterId,
        String matchSource
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("completed", true);
        payload.put("trace_id", traceId);
        payload.put("record_id", recordId);
        payload.put("request_hash", requestHash);
        payload.put("protocol", response.protocol());
        payload.put("source_type", source.type());
        payload.put("source_ref", sourceRef);
        payload.put("chapter_id", chapterId);
        payload.put("match_source", matchSource);
        payload.put("version_label", response.request().path("version").asText("1.0"));
        payload.put("structure", response.request().path("structure").asText());
        payload.put("operation", response.request().path("operation").asText());
        payload.put("step_count", response.animationData().steps().size());
        return payload;
    }

    private Optional<String> existingRecord(
        long userId,
        String traceId,
        String requestHash,
        DsvpEvidenceSource source,
        String sourceRef,
        String chapterId
    ) {
        var rows = jdbc.query(
            """
            SELECT s.request_hash, s.animation_record_id, s.source_type, s.source_ref,
                   a.chapter_id, a.user_id
            FROM dsvp_request_snapshots s
            LEFT JOIN animation_records a ON a.id = s.animation_record_id
            WHERE s.id = ?
            """,
            (row, index) -> new ExistingSnapshot(
                row.getString("request_hash"),
                row.getString("animation_record_id"),
                row.getString("source_type"),
                row.getString("source_ref"),
                row.getString("chapter_id"),
                row.getObject("user_id") == null ? null : row.getLong("user_id")
            ),
            traceId
        );
        if (rows.isEmpty()) return Optional.empty();
        ExistingSnapshot existing = rows.get(0);
        if (!requestHash.equals(existing.requestHash())
            || !source.type().equals(existing.sourceType())
            || !java.util.Objects.equals(sourceRef, existing.sourceRef())
            || !chapterId.equals(existing.chapterId())
            || existing.userId() == null
            || existing.userId() != userId
            || existing.animationRecordId() == null) {
            throw new ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                "DSVP_TRACE_COLLISION",
                "DSVP trace id is already bound to different evidence"
            );
        }
        return Optional.of(existing.animationRecordId());
    }

    private DsvpSimulationResponse persisted(
        DsvpSimulationResponse response,
        String recordId,
        DsvpEvidenceResolution resolution
    ) {
        return new DsvpSimulationResponse(
            response.protocol(),
            response.request(),
            response.trace(),
            response.animationData(),
            recordId,
            true,
            recordId,
            resolution.chapterId(),
            resolution.matchSource()
        );
    }

    private DsvpSimulationResponse preview(DsvpSimulationResponse response) {
        return new DsvpSimulationResponse(
            response.protocol(),
            response.request(),
            response.trace(),
            response.animationData(),
            null,
            false,
            null,
            null,
            "NONE"
        );
    }

    private DsvpSimulationResponse withTraceId(DsvpSimulationResponse response, String traceId) {
        ObjectNode trace = (ObjectNode) response.trace().deepCopy();
        trace.put("trace_id", traceId);
        return new DsvpSimulationResponse(
            response.protocol(),
            response.request(),
            trace,
            response.animationData(),
            null
        );
    }

    private String evidenceTraceId(long userId, String requestJson) {
        return "dsvp_" + sha256(userId + ":" + requestJson).substring(0, 20);
    }

    private record ExistingSnapshot(
        String requestHash,
        String animationRecordId,
        String sourceType,
        String sourceRef,
        String chapterId,
        Long userId
    ) {
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash DSVP request", error);
        }
    }
}
