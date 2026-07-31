package com.feng.dsagent.animation;

public record AnimationStep(
    String op,
    String label,
    String note,
    Object value,
    Integer index,
    Integer node,
    Integer i,
    Integer j,
    String key,
    String val
) {

    public AnimationStep {
        label = label == null || label.isBlank() ? note : label;
    }

    public AnimationStep(String op, String note, Object value) {
        this(op, note, note, value, null, null, null, null, null, null);
    }
}
