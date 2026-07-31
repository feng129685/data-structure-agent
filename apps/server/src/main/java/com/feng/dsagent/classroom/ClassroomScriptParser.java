package com.feng.dsagent.classroom;

import com.feng.dsagent.common.ApiException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class ClassroomScriptParser {

    private static final Set<String> STEP_TYPES = Set.of(
        "explain", "question", "discussion", "blackboard", "summary"
    );
    private static final Set<String> ROLES = Set.of("teacher", "assistant", "student");

    private final ObjectMapper objectMapper;

    public ClassroomScriptParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ClassroomScriptPlan parse(String scriptJson) {
        try {
            JsonNode root = objectMapper.readTree(scriptJson);
            if (root == null || !root.isObject()) {
                throw invalid();
            }
            if (root.path("steps").isArray()) {
                return parseSteps(root);
            }
            if (root.path("stages").isObject()) {
                return parseLegacy(root.path("stages"));
            }
            throw invalid();
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw invalid();
        }
    }

    private ClassroomScriptPlan parseSteps(JsonNode root) {
        String lessonId = requiredText(root, "lessonId", 64);
        String title = requiredText(root, "title", 200);
        List<String> objectives = textArray(root.path("objectives"), false, 12, 160);
        JsonNode steps = root.path("steps");
        if (steps.isEmpty() || steps.size() > 40) {
            throw invalid();
        }

        List<JsonNode> normalizedSteps = new ArrayList<>();
        JsonNode question = null;
        for (JsonNode step : steps) {
            validateStep(step);
            JsonNode copy = step.deepCopy();
            normalizedSteps.add(copy);
            if (question == null && "question".equals(copy.path("type").asText())) {
                question = copy;
            }
        }

        EnumMap<ClassroomState, JsonNode> stages = new EnumMap<>(ClassroomState.class);
        ObjectNode opening = objectMapper.createObjectNode();
        opening.put("type", "opening");
        opening.put("lessonId", lessonId);
        opening.put("title", title);
        opening.set("objectives", root.path("objectives").isArray()
            ? root.path("objectives").deepCopy()
            : objectMapper.createArrayNode());
        stages.put(ClassroomState.OPENING, opening);

        JsonNode explanation = first(normalizedSteps, "explain");
        JsonNode publicQuestion = publicQuestion(question);
        stages.put(ClassroomState.EXPLAIN, nodeOrEmpty(explanation));
        stages.put(ClassroomState.QUESTION, nodeOrEmpty(publicQuestion));
        stages.put(ClassroomState.WAITING, nodeOrEmpty(publicQuestion));
        stages.put(ClassroomState.DISCUSS, nodeOrEmpty(first(normalizedSteps, "discussion")));
        JsonNode blackboard = first(normalizedSteps, "blackboard");
        if (blackboard == null) {
            blackboard = normalizedSteps.stream()
                .filter(step -> step.hasNonNull("animationRef") || step.hasNonNull("codeRef"))
                .findFirst()
                .orElse(null);
        }
        stages.put(ClassroomState.BLACKBOARD, nodeOrEmpty(blackboard));
        JsonNode summary = first(normalizedSteps, "summary");
        if (summary == null) {
            ObjectNode generated = objectMapper.createObjectNode();
            generated.put("type", "summary");
            generated.put("title", title + "课堂总结");
            summary = generated;
        }
        stages.put(ClassroomState.SUMMARY, summary);
        return new ClassroomScriptPlan(
            lessonId,
            title,
            objectives,
            stages,
            question == null ? objectMapper.createObjectNode() : question,
            false
        );
    }

    private ClassroomScriptPlan parseLegacy(JsonNode stagesNode) {
        EnumMap<ClassroomState, JsonNode> stages = new EnumMap<>(ClassroomState.class);
        for (ClassroomState state : ClassroomState.values()) {
            JsonNode stage = stagesNode.path(state.name());
            stages.put(state, stage.isObject() ? stage.deepCopy() : objectMapper.createObjectNode());
        }
        return new ClassroomScriptPlan(
            "legacy",
            "",
            List.of(),
            stages,
            objectMapper.createObjectNode(),
            true
        );
    }

    private void validateStep(JsonNode step) {
        if (!step.isObject()) {
            throw invalid();
        }
        String type = requiredText(step, "type", 32).toLowerCase(Locale.ROOT);
        if (!STEP_TYPES.contains(type)) {
            throw invalid();
        }
        if (step.has("role")) {
            String role = requiredText(step, "role", 32).toLowerCase(Locale.ROOT);
            if (!ROLES.contains(role)) {
                throw invalid();
            }
        }
        if ("question".equals(type)) {
            requiredText(step, "prompt", 1000);
            textArray(step.path("expected"), true, 10, 200);
            textArray(step.path("misconceptions"), false, 20, 200);
        } else if ("explain".equals(type)
                && !hasText(step, "content")
                && !hasText(step, "contentRef")
                && !hasText(step, "animationRef")
                && !hasText(step, "codeRef")) {
            throw invalid();
        }
    }

    private JsonNode publicQuestion(JsonNode question) {
        if (question == null || !question.isObject()) {
            return null;
        }
        ObjectNode visible = (ObjectNode) question.deepCopy();
        visible.remove("expected");
        visible.remove("misconceptions");
        visible.remove("misconceptionFeedback");
        return visible;
    }

    private JsonNode first(List<JsonNode> steps, String type) {
        return steps.stream().filter(step -> type.equals(step.path("type").asText())).findFirst().orElse(null);
    }

    private JsonNode nodeOrEmpty(JsonNode value) {
        return value == null ? objectMapper.createObjectNode() : value.deepCopy();
    }

    private String requiredText(JsonNode node, String field, int maximumLength) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank() || value.codePointCount(0, value.length()) > maximumLength) {
            throw invalid();
        }
        return value;
    }

    private List<String> textArray(JsonNode values, boolean required, int maximumItems, int maximumLength) {
        if (!values.isArray()) {
            if (required) {
                throw invalid();
            }
            return List.of();
        }
        if ((required && values.isEmpty()) || values.size() > maximumItems) {
            throw invalid();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid();
            }
            String normalized = value.asText().trim();
            if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > maximumLength) {
                throw invalid();
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private boolean hasText(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank();
    }

    private ApiException invalid() {
        return new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "CLASSROOM_SCRIPT_INVALID",
            "课堂脚本格式无效"
        );
    }
}
