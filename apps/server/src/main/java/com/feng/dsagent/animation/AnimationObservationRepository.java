package com.feng.dsagent.animation;

import java.util.Optional;

interface AnimationObservationRepository {

    Optional<AnimationRecord> findOwned(long userId, String recordId);

    void appendObservation(long userId, String recordId, String observation);
}
