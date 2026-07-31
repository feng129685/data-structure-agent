package com.feng.dsagent.classroom;

import java.util.Objects;

public record ClassroomStatus(ClassroomState state, boolean paused) {

    public ClassroomStatus {
        Objects.requireNonNull(state, "state must not be null");
    }
}
