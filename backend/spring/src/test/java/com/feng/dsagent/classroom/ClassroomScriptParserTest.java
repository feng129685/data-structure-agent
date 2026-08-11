package com.feng.dsagent.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClassroomScriptParserTest {

    private final ClassroomScriptParser parser = new ClassroomScriptParser(new ObjectMapper());

    @Test
    void parsesTheTeacherApprovedStepBasedContract() {
        ClassroomScriptPlan plan = parser.parse("""
            {
              "lessonId":"03-stack-queue-01",
              "title":"栈的定义与基本操作",
              "objectives":["理解后进先出","掌握 push 和 pop"],
              "steps":[
                {
                  "type":"explain",
                  "role":"teacher",
                  "contentRef":"slide-03",
                  "animationRef":"stack-push"
                },
                {
                  "type":"question",
                  "role":"teacher",
                  "prompt":"依次入栈 A、B、C 后，第一次出栈得到什么？",
                  "expected":["C"],
                  "misconceptions":["A","B"]
                }
              ]
            }
            """);

        assertThat(plan.lessonId()).isEqualTo("03-stack-queue-01");
        assertThat(plan.objectives()).containsExactly("理解后进先出", "掌握 push 和 pop");
        assertThat(plan.stage(ClassroomState.EXPLAIN).path("animationRef").asText()).isEqualTo("stack-push");
        assertThat(plan.stage(ClassroomState.WAITING).path("prompt").asText()).contains("第一次出栈");
        assertThat(plan.stage(ClassroomState.WAITING).has("expected")).isFalse();
        assertThat(plan.question().path("expected").get(0).asText()).isEqualTo("C");
    }

    @Test
    void keepsLegacyStageScriptsReadableDuringMigration() {
        ClassroomScriptPlan plan = parser.parse("""
            {"stages":{"OPENING":{"speaker":"teacher","content":"欢迎进入课堂"}}}
            """);

        assertThat(plan.legacy()).isTrue();
        assertThat(plan.stage(ClassroomState.OPENING).path("content").asText()).isEqualTo("欢迎进入课堂");
    }

    @Test
    void rejectsQuestionsWithoutExpectedAnswers() {
        assertThatThrownBy(() -> parser.parse("""
            {
              "lessonId":"broken",
              "title":"无效脚本",
              "steps":[{"type":"question","role":"teacher","prompt":"答案是什么？"}]
            }
            """))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code()).isEqualTo("CLASSROOM_SCRIPT_INVALID")
            );
    }

    @Test
    void acceptsValidatedDsvpAnimationReferencesAndRejectsUnknownOperations() {
        ClassroomScriptPlan plan = parser.parse("""
            {
              "lessonId":"03-dsvp",
              "title":"DSVP classroom",
              "steps":[{
                "type":"explain",
                "role":"teacher",
                "animationRef":{
                  "protocol":"dsvp/1.0",
                  "request":{
                    "version":"1.0",
                    "structure":"stack",
                    "operation":"push",
                    "params":{"value":3,"capacity":8},
                    "initial_state":{"data":[1,2],"metadata":{"capacity":8}}
                  }
                }
              }]
            }
            """);
        assertThat(plan.stage(ClassroomState.EXPLAIN).path("animationRef").path("protocol").asText())
            .isEqualTo("dsvp/1.0");

        assertThatThrownBy(() -> parser.parse("""
            {
              "lessonId":"03-dsvp-broken",
              "title":"Broken DSVP classroom",
              "steps":[{
                "type":"explain",
                "animationRef":{
                  "protocol":"dsvp/1.0",
                  "request":{"version":"1.0","structure":"stack","operation":"execute","params":{},"initial_state":{"data":[]}}
                }
              }]
            }
            """))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code()).isEqualTo("CLASSROOM_SCRIPT_INVALID")
            );
    }

    @Test
    void acceptsLegacyDsvpProtocolAliasAndNormalizesTheStoredPlan() {
        ClassroomScriptPlan plan = parser.parse("""
            {
              "lessonId":"03-dsvp-legacy",
              "title":"Legacy DSVP classroom",
              "steps":[{
                "type":"explain",
                "role":"teacher",
                "animationRef":{
                  "protocol":"dsvp/1",
                  "request":{
                    "version":"1.0",
                    "structure":"stack",
                    "operation":"push",
                    "params":{"value":7},
                    "initial_state":{"data":[2,5]}
                  }
                }
              }]
            }
            """);

        assertThat(plan.stage(ClassroomState.EXPLAIN).path("animationRef").path("protocol").asText())
            .isEqualTo("dsvp/1.0");
    }
}
