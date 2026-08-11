package com.feng.dsagent.animation;

import java.util.Optional;

interface AnimationObservationRepository {

    Optional<AnimationRecord> findOwned(long userId, String recordId);

    void appendObservation(long userId, String recordId, String observation);

    /**
     * Provenance-aware append used by HTTP workflows. Existing in-memory test
     * repositories may keep the legacy three-argument behavior.
     */
    default void appendObservation(
        long userId,
        String recordId,
        String observation,
        String sourceType,
        String sourceRef,
        String versionLabel
    ) {
        appendObservation(userId, recordId, observation);
    }
}
