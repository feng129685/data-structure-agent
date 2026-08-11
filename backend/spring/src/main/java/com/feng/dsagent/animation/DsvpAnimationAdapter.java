package com.feng.dsagent.animation;

import com.feng.dsagent.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class DsvpAnimationAdapter {

    static final String VERSION = "1.0";
    static final int MAXIMUM_REQUEST_BYTES = 256 * 1024;
    static final int MAXIMUM_SOURCE_REF_LENGTH = 160;

    private static final Set<String> REQUEST_FIELDS = Set.of(
        "version", "structure", "operation", "params", "initial_state", "options", "source_ref",
        "context", "chapter_id", "chapterId", "lesson_id", "lessonId", "presentation_id",
        "presentationId", "presentation_page_id", "presentationPageId", "classroom_session_id",
        "classroomSessionId"
    );
    private static final Set<String> CONTEXT_FIELDS = Set.of(
        "chapter_id", "lesson_id", "presentation_id", "presentation_page_id", "classroom_session_id",
        "source_type", "source_ref"
    );
    private static final Set<String> PARAM_FIELDS = Set.of(
        "value", "capacity", "position", "index", "node", "i", "j", "key", "val"
    );
    private static final Map<String, Set<String>> OPERATIONS = Map.of(
        "stack", Set.of("push", "pop", "peek"),
        "queue", Set.of("enqueue", "dequeue", "peek"),
        "sequential_list", Set.of("insert", "delete", "merge"),
        "linked_list", Set.of("append", "insert", "delete", "find"),
        "tree", Set.of("visit", "highlight"),
        "graph", Set.of("bfs", "dfs", "visit"),
        "heap", Set.of("insert", "extract", "peek"),
        "hash", Set.of("put", "get", "delete"),
        "array", Set.of("set", "insert", "delete", "swap", "get")
    );
    private static final Map<String, String> RENDERER_TYPES = Map.of(
        "sequential_list", "array",
        "linked_list", "list",
        "graph", "tree"
    );

    private final ObjectMapper objectMapper;
    private final AnimationValidator validator;

    public DsvpAnimationAdapter(ObjectMapper objectMapper, AnimationValidator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public DsvpSimulationResponse adapt(JsonNode input) {
        requireObject(input, "request");
        if (input.toString().getBytes(StandardCharsets.UTF_8).length > MAXIMUM_REQUEST_BYTES) {
            throw invalid("DSVP_REQUEST_TOO_LARGE", "DSVP request is too large");
        }
        rejectUnknown(input, REQUEST_FIELDS, "request");
        validateSourceRef(input);
        validateContext(input);
        String version = text(input, "version", 16);
        if (!VERSION.equals(version)) {
            throw invalid("DSVP_VERSION_UNSUPPORTED", "Unsupported DSVP version");
        }
        String structure = text(input, "structure", 32);
        String operation = text(input, "operation", 32);
        if (!OPERATIONS.getOrDefault(structure, Set.of()).contains(operation)) {
            throw invalid("DSVP_OPERATION_UNSUPPORTED", "Unsupported DSVP structure or operation");
        }

        JsonNode params = input.path("params");
        requireObject(params, "params");
        rejectUnknown(params, PARAM_FIELDS, "params");
        JsonNode initialState = input.path("initial_state");
        requireObject(initialState, "initial_state");
        JsonNode data = initialState.path("data");
        if (!data.isArray()) {
            throw invalid("DSVP_INITIAL_STATE_INVALID", "initial_state.data must be an array");
        }
        if (data.size() > AnimationValidator.MAX_INITIAL_ITEMS) {
            throw invalid("DSVP_INITIAL_STATE_TOO_LARGE", "initial_state.data is too large");
        }

        int capacity = integer(params.path("capacity"), Math.max(10, data.size() + 1), 1, 100, "capacity");
        if (!"sequential_list".equals(structure) || !"merge".equals(operation)) {
            if (data.size() > capacity) {
                throw invalid("DSVP_INITIAL_STATE_OVERFLOW", "initial_state exceeds capacity");
            }
        }

        List<Object> initial = convertArray(data);
        List<AnimationStep> steps = buildSteps(structure, operation, params, initial);
        AnimationDefinition definition = new AnimationDefinition(
            true,
            RENDERER_TYPES.getOrDefault(structure, structure),
            abbreviate(structure + " " + operation, AnimationValidator.MAX_TITLE_LENGTH),
            abbreviate("DSVP " + VERSION + " " + structure + "/" + operation, AnimationValidator.MAX_DESCRIPTION_LENGTH),
            normalizedInitial(structure, operation, initial),
            steps
        );
        AnimationValidationResult validation = validator.validate(definition);
        if (!validation.valid()) {
            throw invalid("DSVP_ANIMATION_INVALID", "DSVP request cannot be adapted to the renderer contract");
        }

        ObjectNode normalizedRequest = normalizeRequest(input, capacity);
        return new DsvpSimulationResponse(
            "dsvp/1.0",
            normalizedRequest,
            trace(normalizedRequest, structure, operation, definition),
            definition,
            null
        );
    }

    private List<AnimationStep> buildSteps(
        String structure,
        String operation,
        JsonNode params,
        List<Object> initial
    ) {
        if ("sequential_list".equals(structure) && "merge".equals(operation)) {
            if (initial.size() != 2 || !(initial.get(0) instanceof List<?>) || !(initial.get(1) instanceof List<?> right)) {
                throw invalid("DSVP_INITIAL_STATE_INVALID", "merge requires two arrays");
            }
            int leftSize = ((List<?>) initial.get(0)).size();
            if (right.isEmpty()) {
                return List.of(step("get", "merge", "Inspect the already merged sequence", null, 0, null, null, null, null, null));
            }
            List<AnimationStep> result = new ArrayList<>();
            for (int index = 0; index < Math.min(right.size(), AnimationValidator.MAX_STEPS); index++) {
                result.add(step("insert", "merge", "Insert the next ordered value", right.get(index), leftSize + index, null, null, null, null, null));
            }
            return List.copyOf(result);
        }

        String rendererOperation = operation;
        if ("graph".equals(structure)) {
            rendererOperation = "visit".equals(operation) ? "visit" : "highlight";
        }
        Object value = scalar(params.get("value"), "value", false);
        Integer index = optionalInteger(params, "position", 1, 1024);
        if (index != null) index -= 1;
        Integer directIndex = optionalInteger(params, "index", 0, 1024);
        if (directIndex != null) index = directIndex;
        Integer node = optionalInteger(params, "node", 0, 64);
        Integer i = optionalInteger(params, "i", 0, 1024);
        Integer j = optionalInteger(params, "j", 0, 1024);
        String key = optionalText(params, "key", AnimationValidator.MAX_VALUE_LENGTH);
        String val = optionalText(params, "val", AnimationValidator.MAX_VALUE_LENGTH);

        if (Set.of("push", "enqueue", "insert", "set", "append").contains(operation) && value == null) {
            throw invalid("DSVP_VALUE_REQUIRED", "value is required for this operation");
        }
        if ("put".equals(operation) && (key == null || val == null)) {
            throw invalid("DSVP_VALUE_REQUIRED", "key and val are required for hash put");
        }
        if ("swap".equals(operation) && (i == null || j == null)) {
            throw invalid("DSVP_VALUE_REQUIRED", "i and j are required for array swap");
        }
        return List.of(step(
            rendererOperation,
            operation,
            "Execute " + structure + " " + operation,
            value,
            index,
            node,
            i,
            j,
            key,
            val
        ));
    }

    private AnimationStep step(
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
        return new AnimationStep(op, abbreviate(label, 48), abbreviate(note, 240), value, index, node, i, j, key, val);
    }

    private List<Object> normalizedInitial(String structure, String operation, List<Object> values) {
        if (!"sequential_list".equals(structure) || !"merge".equals(operation)) {
            return values;
        }
        return List.copyOf((List<?>) values.get(0));
    }

    private ObjectNode normalizeRequest(JsonNode input, int capacity) {
        ObjectNode normalized = (ObjectNode) input.deepCopy();
        ObjectNode params = normalized.withObject("params");
        if (!params.has("capacity")) params.put("capacity", capacity);
        ObjectNode initial = normalized.withObject("initial_state");
        ObjectNode metadata = initial.withObject("metadata");
        metadata.put("capacity", capacity);
        if (!normalized.has("options")) {
            ObjectNode options = normalized.putObject("options");
            options.put("language", "c");
            options.put("explain_level", "beginner");
        }
        if (!normalized.has("source_ref")) normalized.put("source_ref", "");
        normalizeContext(normalized);
        return normalized;
    }

    private void validateContext(JsonNode request) {
        if (request.has("context")) {
            JsonNode context = request.get("context");
            requireObject(context, "context");
            rejectUnknown(context, CONTEXT_FIELDS, "context");
            for (String field : CONTEXT_FIELDS) {
                if (context.has(field)) validateContextText(context, field);
            }
        }
        for (String field : Set.of(
            "chapter_id", "chapterId", "lesson_id", "lessonId", "presentation_id", "presentationId",
            "presentation_page_id", "presentationPageId", "classroom_session_id", "classroomSessionId"
        )) {
            if (request.has(field)) validateContextText(request, field);
        }
    }

    private void validateContextText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return;
        if (!value.isTextual() || value.asText().trim().length() > MAXIMUM_SOURCE_REF_LENGTH) {
            throw invalid("DSVP_CONTEXT_INVALID", field + " must be a bounded string");
        }
    }

    private void normalizeContext(ObjectNode request) {
        ObjectNode context;
        if (request.has("context")) {
            context = (ObjectNode) request.get("context");
        } else {
            context = objectMapper.createObjectNode();
        }
        copyAlias(request, context, "chapter_id", "chapterId");
        copyAlias(request, context, "lesson_id", "lessonId");
        copyAlias(request, context, "presentation_id", "presentationId");
        copyAlias(request, context, "presentation_page_id", "presentationPageId");
        copyAlias(request, context, "classroom_session_id", "classroomSessionId");
        if (request.has("source_ref") && !context.has("source_ref")) {
            context.set("source_ref", request.get("source_ref"));
        }
        if (context.isEmpty()) {
            request.remove("context");
        } else {
            request.set("context", context);
        }
        removeAliases(request);
    }

    private void copyAlias(ObjectNode request, ObjectNode context, String canonical, String alias) {
        if (!context.has(canonical) && request.has(alias)) context.set(canonical, request.get(alias));
        if (!context.has(canonical) && request.has(canonical)) context.set(canonical, request.get(canonical));
    }

    private void removeAliases(ObjectNode request) {
        for (String alias : Set.of(
            "chapter_id", "chapterId", "lesson_id", "lessonId", "presentation_id", "presentationId",
            "presentation_page_id", "presentationPageId", "classroom_session_id", "classroomSessionId"
        )) {
            request.remove(alias);
        }
    }

    private ObjectNode trace(ObjectNode request, String structure, String operation, AnimationDefinition definition) {
        ObjectNode trace = objectMapper.createObjectNode();
        trace.put("version", VERSION);
        trace.put("protocol", "dsvp/1.0");
        trace.put("trace_id", traceId(request));
        trace.put("structure", structure);
        trace.put("operation", operation);
        trace.put("source_ref", request.path("source_ref").asText(""));
        ArrayNode steps = trace.putArray("steps");
        for (int index = 0; index < definition.steps().size(); index++) {
            AnimationStep animationStep = definition.steps().get(index);
            ObjectNode step = steps.addObject();
            step.put("step_id", index + 1);
            step.put("phase", "operation");
            step.put("title", animationStep.label());
            step.put("description", animationStep.note());
            ObjectNode action = step.putObject("action");
            action.put("type", animationStep.op());
            if (animationStep.value() != null) action.set("value", objectMapper.valueToTree(animationStep.value()));
        }
        trace.putArray("errors");
        trace.putArray("warnings");
        return trace;
    }

    private String traceId(JsonNode request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(request.toString().getBytes(StandardCharsets.UTF_8));
            return "dsvp_" + HexFormat.of().formatHex(digest).substring(0, 20);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create DSVP trace id", error);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> convertArray(JsonNode node) {
        return List.copyOf((List<Object>) objectMapper.convertValue(node, List.class));
    }

    private Object scalar(JsonNode node, String field, boolean required) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            if (required) throw invalid("DSVP_VALUE_REQUIRED", field + " is required");
            return null;
        }
        if (node.isTextual()) return abbreviate(node.asText(), AnimationValidator.MAX_VALUE_LENGTH);
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.numberValue();
        throw invalid("DSVP_VALUE_INVALID", field + " must be scalar");
    }

    private int integer(JsonNode node, int fallback, int minimum, int maximum, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        if (!node.isIntegralNumber() || node.asInt() < minimum || node.asInt() > maximum) {
            throw invalid("DSVP_VALUE_INVALID", field + " is outside the valid range");
        }
        return node.asInt();
    }

    private Integer optionalInteger(JsonNode node, String field, int minimum, int maximum) {
        if (node == null || !node.has(field)) return null;
        return integer(node.get(field), 0, minimum, maximum, field);
    }

    private String optionalText(JsonNode node, String field, int maximum) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        String value = node.get(field).asText("").trim();
        return value.isBlank() ? null : abbreviate(value, maximum);
    }

    private String text(JsonNode node, String field, int maximum) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank() || value.length() > maximum) {
            throw invalid("DSVP_REQUEST_INVALID", field + " is required");
        }
        return value;
    }

    private void requireObject(JsonNode value, String path) {
        if (value == null || !value.isObject()) {
            throw invalid("DSVP_REQUEST_INVALID", path + " must be an object");
        }
    }

    private void rejectUnknown(JsonNode value, Set<String> allowed, String path) {
        for (String name : value.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid("DSVP_UNEXPECTED_FIELD", path + " contains an unsupported field");
            }
        }
    }

    private void validateSourceRef(JsonNode request) {
        if (!request.has("source_ref")) {
            return;
        }
        JsonNode sourceRef = request.get("source_ref");
        if (!sourceRef.isTextual()
                || sourceRef.asText().codePointCount(0, sourceRef.asText().length()) > MAXIMUM_SOURCE_REF_LENGTH) {
            throw invalid("DSVP_REQUEST_INVALID", "source_ref must be a string of at most 160 characters");
        }
    }

    private String abbreviate(String value, int maximum) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maximum));
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
