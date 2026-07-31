package com.feng.dsagent.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record AnimationDefinition(
    Boolean animation,
    String type,
    String title,
    String description,
    List<Object> initial,
    List<AnimationStep> steps
) {

    public AnimationDefinition {
        animation = animation == null ? Boolean.TRUE : animation;
        description = description == null ? "" : description;
        initial = immutable(initial);
        steps = immutable(steps);
    }

    public AnimationDefinition(String type, String title, List<AnimationStep> steps) {
        this(Boolean.TRUE, type, title, "", List.of(), steps);
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null) {
            return List.of();
        }
        if (values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
