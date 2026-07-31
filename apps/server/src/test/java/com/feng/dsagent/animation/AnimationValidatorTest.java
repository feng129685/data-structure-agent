package com.feng.dsagent.animation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnimationValidatorTest {

    private final AnimationValidator validator = new AnimationValidator();

    @Test
    void acceptsAValidStackAnimation() {
        AnimationDefinition animation = new AnimationDefinition(
                "stack",
                "栈的入栈过程",
                List.of(
                        new AnimationStep("push", "把 3 压入栈顶", 3),
                        new AnimationStep("peek", "观察新的栈顶", null)));

        AnimationValidationResult result = validator.validate(animation);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void reportsRequiredFieldsWithStructuredPathsAndCodes() {
        AnimationDefinition animation = new AnimationDefinition("", "", List.of(
                new AnimationStep("", "", null)));

        AnimationValidationResult result = validator.validate(animation);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("type", "REQUIRED"),
                        org.assertj.core.groups.Tuple.tuple("title", "REQUIRED"),
                        org.assertj.core.groups.Tuple.tuple("steps[0].op", "REQUIRED"),
                        org.assertj.core.groups.Tuple.tuple("steps[0].note", "REQUIRED"));
    }

    @Test
    void rejectsUnsupportedTypesAndOperations() {
        AnimationDefinition unsupportedType = new AnimationDefinition(
                "graph",
                "图动画",
                List.of(new AnimationStep("visit", "访问节点", 1)));
        AnimationDefinition unsupportedOperation = new AnimationDefinition(
                "stack",
                "危险操作",
                List.of(new AnimationStep("executeScript", "不应执行", null)));

        AnimationValidationResult typeResult = validator.validate(unsupportedType);
        AnimationValidationResult operationResult = validator.validate(unsupportedOperation);

        assertThat(typeResult.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("type", "UNSUPPORTED"));
        assertThat(operationResult.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("steps[0].op", "UNSUPPORTED"));
    }

    @Test
    void enforcesStepCountLimits() {
        AnimationDefinition noSteps = new AnimationDefinition("stack", "空动画", List.of());
        AnimationDefinition tooManySteps = new AnimationDefinition(
                "stack",
                "步骤过多",
                java.util.stream.IntStream.range(0, AnimationValidator.MAX_STEPS + 1)
                        .mapToObj(index -> new AnimationStep("peek", "观察栈顶", null))
                        .toList());

        AnimationValidationResult emptyResult = validator.validate(noSteps);
        AnimationValidationResult overflowResult = validator.validate(tooManySteps);

        assertThat(emptyResult.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("steps", "TOO_FEW"));
        assertThat(overflowResult.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("steps", "TOO_MANY"));
    }

    @Test
    void enforcesTextLengthLimits() {
        AnimationDefinition animation = new AnimationDefinition(
                "stack",
                "题".repeat(AnimationValidator.MAX_TITLE_LENGTH + 1),
                List.of(new AnimationStep(
                        "p".repeat(AnimationValidator.MAX_OPERATION_LENGTH + 1),
                        "说".repeat(AnimationValidator.MAX_NOTE_LENGTH + 1),
                        "值".repeat(AnimationValidator.MAX_VALUE_LENGTH + 1))));

        AnimationValidationResult result = validator.validate(animation);

        assertThat(result.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("title", "TOO_LONG"),
                        org.assertj.core.groups.Tuple.tuple("steps[0].op", "TOO_LONG"),
                        org.assertj.core.groups.Tuple.tuple("steps[0].note", "TOO_LONG"),
                        org.assertj.core.groups.Tuple.tuple("steps[0].value", "TOO_LONG"));
    }

    @Test
    void requiresValuesOnlyForOperationsThatNeedThem() {
        AnimationDefinition missingPushValue = new AnimationDefinition(
                "stack",
                "缺少入栈值",
                List.of(new AnimationStep("push", "准备入栈", null)));
        AnimationDefinition popWithoutValue = new AnimationDefinition(
                "stack",
                "出栈",
                List.of(new AnimationStep("pop", "弹出栈顶", null)));

        AnimationValidationResult invalid = validator.validate(missingPushValue);
        AnimationValidationResult valid = validator.validate(popWithoutValue);

        assertThat(invalid.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("steps[0].value", "REQUIRED"));
        assertThat(valid.valid()).isTrue();
    }

    @Test
    void rejectsUnsupportedValueShapesAndNonFiniteNumbers() {
        AnimationDefinition objectValue = new AnimationDefinition(
                "stack",
                "对象值",
                List.of(new AnimationStep("push", "对象不能进入动画协议", List.of("x"))));
        AnimationDefinition infiniteValue = new AnimationDefinition(
                "heap",
                "无限值",
                List.of(new AnimationStep("insert", "无穷数不能入堆", Double.POSITIVE_INFINITY)));

        AnimationValidationResult objectResult = validator.validate(objectValue);
        AnimationValidationResult infiniteResult = validator.validate(infiniteValue);

        assertThat(objectResult.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("steps[0].value", "INVALID_TYPE"));
        assertThat(infiniteResult.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("steps[0].value", "INVALID_NUMBER"));
    }

    @Test
    void rejectsNullAnimationAndNullStepsWithoutThrowing() {
        AnimationValidationResult nullAnimation = validator.validate(null);
        AnimationValidationResult nullStep = validator.validate(
                new AnimationDefinition("stack", "空步骤项", java.util.Arrays.asList((AnimationStep) null)));

        assertThat(nullAnimation.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("animation", "REQUIRED"));
        assertThat(nullStep.errors())
                .extracting(AnimationValidationError::path, AnimationValidationError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("steps[0]", "REQUIRED"));
    }
}
