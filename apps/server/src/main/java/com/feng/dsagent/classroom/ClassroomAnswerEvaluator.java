package com.feng.dsagent.classroom;

import java.util.Locale;
import tools.jackson.databind.JsonNode;

public final class ClassroomAnswerEvaluator {

    public ClassroomAnswerEvaluation evaluate(JsonNode question, String answer) {
        String normalizedAnswer = normalize(answer);
        JsonNode expected = question == null ? null : question.path("expected");
        if (matches(expected, normalizedAnswer)) {
            return new ClassroomAnswerEvaluation(
                ClassroomAnswerStatus.CORRECT,
                null,
                "回答正确，可以继续观察算法状态变化。"
            );
        }

        JsonNode misconceptions = question == null ? null : question.path("misconceptions");
        String misconception = match(misconceptions, normalizedAnswer);
        if (misconception != null) {
            String configuredFeedback = question.path("misconceptionFeedback").path(misconception).asText("").trim();
            String feedback = configuredFeedback.isBlank()
                ? "这个答案对应课堂脚本中的常见误区，请结合当前演示重新检查操作顺序。"
                : configuredFeedback;
            return new ClassroomAnswerEvaluation(ClassroomAnswerStatus.MISCONCEPTION, misconception, feedback);
        }
        return new ClassroomAnswerEvaluation(
            ClassroomAnswerStatus.INCORRECT,
            null,
            "答案尚未命中预期结论，需要进入讨论环节进一步分析。"
        );
    }

    private boolean matches(JsonNode values, String answer) {
        return match(values, answer) != null;
    }

    private String match(JsonNode values, String answer) {
        if (values == null || !values.isArray() || answer.isBlank()) {
            return null;
        }
        for (JsonNode value : values) {
            if (value.isTextual() && normalize(value.asText()).equals(answer)) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
