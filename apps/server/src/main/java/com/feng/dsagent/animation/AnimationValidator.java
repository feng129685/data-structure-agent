package com.feng.dsagent.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AnimationValidator {

    public static final int MAX_TYPE_LENGTH = 16;
    public static final int MAX_TITLE_LENGTH = 60;
    public static final int MAX_DESCRIPTION_LENGTH = 240;
    public static final int MAX_INITIAL_ITEMS = 64;
    public static final int MAX_STEPS = 20;
    public static final int MAX_OPERATION_LENGTH = 32;
    public static final int MAX_LABEL_LENGTH = 48;
    public static final int MAX_NOTE_LENGTH = 240;
    public static final int MAX_VALUE_LENGTH = 48;

    private static final Map<String, Set<String>> ALLOWED_OPERATIONS = Map.of(
            "stack", Set.of("push", "pop", "peek"),
            "list", Set.of("append", "insert", "delete", "deleteValue", "find"),
            "tree", Set.of("visit", "highlight"),
            "queue", Set.of("enqueue", "dequeue", "peek"),
            "heap", Set.of("insert", "extract", "peek"),
            "hash", Set.of("put", "get", "delete"),
            "array", Set.of("set", "insert", "delete", "swap", "get"));

    private static final Map<String, Set<String>> VALUE_REQUIRED_OPERATIONS = Map.of(
            "stack", Set.of("push"),
            "list", Set.of("append", "insert", "deleteValue", "find"),
            "queue", Set.of("enqueue"),
            "heap", Set.of("insert"),
            "array", Set.of("set", "insert"));

    public AnimationValidationResult validate(AnimationDefinition animation) {
        List<AnimationValidationError> errors = new ArrayList<>();
        if (animation == null) {
            errors.add(error("animation", "REQUIRED", "animation is required"));
            return AnimationValidationResult.fromErrors(errors);
        }

        if (!Boolean.TRUE.equals(animation.animation())) {
            errors.add(error("animation", "REQUIRED", "animation must be true"));
        }

        validateRequiredText("type", animation.type(), MAX_TYPE_LENGTH, errors);
        validateRequiredText("title", animation.title(), MAX_TITLE_LENGTH, errors);
        validateOptionalText("description", animation.description(), MAX_DESCRIPTION_LENGTH, errors);

        String type = animation.type();
        boolean supportedType = hasText(type) && ALLOWED_OPERATIONS.containsKey(type);
        if (hasText(type) && !supportedType) {
            errors.add(error("type", "UNSUPPORTED", "unsupported animation type: " + type));
        }

        validateInitial(type, supportedType, animation.initial(), errors);

        List<AnimationStep> steps = animation.steps();
        if (steps == null || steps.isEmpty()) {
            errors.add(error("steps", "TOO_FEW", "at least one animation step is required"));
            return AnimationValidationResult.fromErrors(errors);
        }
        if (steps.size() > MAX_STEPS) {
            errors.add(error("steps", "TOO_MANY", "animation supports at most " + MAX_STEPS + " steps"));
        }

        int stepsToValidate = Math.min(steps.size(), MAX_STEPS);
        for (int index = 0; index < stepsToValidate; index++) {
            validateStep(type, supportedType, steps.get(index), index, errors);
        }
        return AnimationValidationResult.fromErrors(errors);
    }

    private void validateStep(
            String type,
            boolean supportedType,
            AnimationStep step,
            int index,
            List<AnimationValidationError> errors) {
        String path = "steps[" + index + "]";
        if (step == null) {
            errors.add(error(path, "REQUIRED", "animation step is required"));
            return;
        }

        validateRequiredText(path + ".op", step.op(), MAX_OPERATION_LENGTH, errors);
        validateRequiredText(path + ".label", step.label(), MAX_LABEL_LENGTH, errors);
        validateRequiredText(path + ".note", step.note(), MAX_NOTE_LENGTH, errors);

        String op = step.op();
        boolean supportedOperation = supportedType
                && hasText(op)
                && ALLOWED_OPERATIONS.get(type).contains(op);
        if (supportedType && hasText(op) && !supportedOperation) {
            errors.add(error(path + ".op", "UNSUPPORTED", "unsupported operation " + op + " for " + type));
        }

        validateValue(path + ".value", type, op, supportedOperation, step.value(), errors);
        validateIndex(path + ".index", step.index(), 1024, errors);
        validateIndex(path + ".node", step.node(), 64, errors);
        validateIndex(path + ".i", step.i(), 1024, errors);
        validateIndex(path + ".j", step.j(), 1024, errors);
        validateOptionalText(path + ".key", step.key(), MAX_VALUE_LENGTH, errors);
        validateOptionalText(path + ".val", step.val(), MAX_VALUE_LENGTH, errors);

        if (supportedOperation && "hash".equals(type) && !hasText(step.key())) {
            errors.add(error(path + ".key", "REQUIRED", "key is required for hash operations"));
        }
        if (supportedOperation && "hash".equals(type) && "put".equals(op) && step.val() == null) {
            errors.add(error(path + ".val", "REQUIRED", "val is required for hash put"));
        }
        if (supportedOperation && "array".equals(type) && "swap".equals(op)
                && (step.i() == null || step.j() == null)) {
            errors.add(error(path, "REQUIRED", "i and j are required for array swap"));
        }
    }

    private void validateInitial(
            String type,
            boolean supportedType,
            List<Object> initial,
            List<AnimationValidationError> errors) {
        if (initial == null) {
            errors.add(error("initial", "REQUIRED", "initial is required"));
            return;
        }
        int maximum = "hash".equals(type) ? 16 : MAX_INITIAL_ITEMS;
        if (initial.size() > maximum) {
            errors.add(error("initial", "TOO_MANY", "initial supports at most " + maximum + " items"));
        }
        int count = Math.min(initial.size(), maximum);
        for (int index = 0; index < count; index++) {
            Object value = initial.get(index);
            String path = "initial[" + index + "]";
            if ("hash".equals(type) && supportedType) {
                validateHashBucket(path, value, errors);
            } else if ("heap".equals(type) && supportedType) {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    errors.add(error(path, "INVALID_TYPE", "heap initial values must be finite numbers"));
                }
            } else {
                validateScalar(path, value, errors);
            }
        }
    }

    private void validateHashBucket(String path, Object value, List<AnimationValidationError> errors) {
        if (!(value instanceof List<?> bucket)) {
            errors.add(error(path, "INVALID_TYPE", "hash initial values must be bucket arrays"));
            return;
        }
        if (bucket.size() > 8) {
            errors.add(error(path, "TOO_MANY", "hash buckets support at most 8 entries"));
        }
        for (int index = 0; index < Math.min(bucket.size(), 8); index++) {
            Object entry = bucket.get(index);
            if (!(entry instanceof Map<?, ?> map) || map.get("key") == null || map.get("val") == null) {
                errors.add(error(path + "[" + index + "]", "INVALID_TYPE", "hash entries require key and val"));
                continue;
            }
            validateOptionalText(path + "[" + index + "].key", String.valueOf(map.get("key")), MAX_VALUE_LENGTH, errors);
            validateOptionalText(path + "[" + index + "].val", String.valueOf(map.get("val")), MAX_VALUE_LENGTH, errors);
        }
    }

    private void validateScalar(String path, Object value, List<AnimationValidationError> errors) {
        if (value instanceof String text) {
            if (length(text) > MAX_VALUE_LENGTH) {
                errors.add(error(path, "TOO_LONG", "value must be at most " + MAX_VALUE_LENGTH + " characters"));
            }
            return;
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                errors.add(error(path, "INVALID_NUMBER", "numeric value must be finite"));
            }
            return;
        }
        if (!(value instanceof Boolean)) {
            errors.add(error(path, "INVALID_TYPE", "value must be a string, number, or boolean"));
        }
    }

    private void validateIndex(String path, Integer value, int maximum, List<AnimationValidationError> errors) {
        if (value != null && (value < 0 || value > maximum)) {
            errors.add(error(path, "OUT_OF_RANGE", path + " must be between 0 and " + maximum));
        }
    }

    private void validateValue(
            String path,
            String type,
            String op,
            boolean supportedOperation,
            Object value,
            List<AnimationValidationError> errors) {
        boolean valueRequired = supportedOperation
                && VALUE_REQUIRED_OPERATIONS.getOrDefault(type, Set.of()).contains(op);
        if (value == null) {
            if (valueRequired) {
                errors.add(error(path, "REQUIRED", "value is required for operation " + op));
            }
            return;
        }

        if (value instanceof String text) {
            if (valueRequired && text.isBlank()) {
                errors.add(error(path, "REQUIRED", "value is required for operation " + op));
            }
            if (length(text) > MAX_VALUE_LENGTH) {
                errors.add(error(path, "TOO_LONG", "value must be at most " + MAX_VALUE_LENGTH + " characters"));
            }
            if ("heap".equals(type) && supportedOperation) {
                errors.add(error(path, "INVALID_TYPE", "heap values must be finite numbers"));
            }
            return;
        }

        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                errors.add(error(path, "INVALID_NUMBER", "numeric value must be finite"));
            }
            return;
        }

        if (!(value instanceof Boolean)) {
            errors.add(error(path, "INVALID_TYPE", "value must be a string, number, or boolean"));
        }
    }

    private void validateRequiredText(
            String path,
            String value,
            int maxLength,
            List<AnimationValidationError> errors) {
        if (!hasText(value)) {
            errors.add(error(path, "REQUIRED", path + " is required"));
            return;
        }
        if (length(value) > maxLength) {
            errors.add(error(path, "TOO_LONG", path + " must be at most " + maxLength + " characters"));
        }
    }

    private void validateOptionalText(
            String path,
            String value,
            int maxLength,
            List<AnimationValidationError> errors) {
        if (value != null && length(value) > maxLength) {
            errors.add(error(path, "TOO_LONG", path + " must be at most " + maxLength + " characters"));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private AnimationValidationError error(String path, String code, String message) {
        return new AnimationValidationError(path, code, message);
    }
}
