package com.feng.dsagent.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClassroomAnswerEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClassroomAnswerEvaluator evaluator = new ClassroomAnswerEvaluator();

    @Test
    void distinguishesCorrectAnswersKnownMisconceptionsAndUnknownAnswers() throws Exception {
        var question = objectMapper.readTree("""
            {"expected":["C"],"misconceptions":["A","B"]}
            """);

        assertThat(evaluator.evaluate(question, " c ").status()).isEqualTo(ClassroomAnswerStatus.CORRECT);
        assertThat(evaluator.evaluate(question, "A").status()).isEqualTo(ClassroomAnswerStatus.MISCONCEPTION);
        assertThat(evaluator.evaluate(question, "A").misconception()).isEqualTo("A");
        assertThat(evaluator.evaluate(question, "D").status()).isEqualTo(ClassroomAnswerStatus.INCORRECT);
    }
}
