package com.feng.dsagent.animation;

import tools.jackson.databind.JsonNode;

public record DsvpSimulationResponse(
    String protocol,
    JsonNode request,
    JsonNode trace,
    AnimationDefinition animationData,
    String recordId,
    boolean evidencePersisted,
    String animationRecordId,
    String resolvedChapterId,
    String matchSource
) {

    /** Keeps the adapter-only response compatible with callers that do not persist evidence. */
    public DsvpSimulationResponse(
        String protocol,
        JsonNode request,
        JsonNode trace,
        AnimationDefinition animationData,
        String recordId
    ) {
        this(
            protocol,
            request,
            trace,
            animationData,
            recordId,
            recordId != null,
            recordId,
            null,
            recordId == null ? "NONE" : "UNRESOLVED"
        );
    }
}
