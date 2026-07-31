package com.feng.dsagent.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.feng.dsagent.animation.AnimationDefinition;
import com.feng.dsagent.animation.AnimationValidator;
import com.feng.dsagent.classroom.ClassroomScriptParser;
import com.feng.dsagent.classroom.ClassroomScriptPlan;
import com.feng.dsagent.classroom.ClassroomState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ContractExampleCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsPublishedAnimationExamplesCompatibleWithTheBackendValidator() throws Exception {
        JsonNode schema = objectMapper.readTree(read("animation.schema.json"));
        assertThat(schema.isObject()).isTrue();

        for (String example : new String[] {
            "examples/animations/stack-push-pop.json",
            "examples/animations/queue-enqueue-dequeue.json"
        }) {
            AnimationDefinition definition = objectMapper.readValue(read(example), AnimationDefinition.class);
            assertThat(new AnimationValidator().validate(definition).valid())
                .as(example)
                .isTrue();
        }
    }

    @Test
    void keepsPublishedClassroomExamplesCompatibleWithTheScriptParser() throws Exception {
        JsonNode schema = objectMapper.readTree(read("classroom-script.schema.json"));
        assertThat(schema.isObject()).isTrue();

        ClassroomScriptParser parser = new ClassroomScriptParser(objectMapper);
        for (String example : new String[] {
            "examples/classroom/stack-lesson.json",
            "examples/classroom/queue-lesson.json"
        }) {
            ClassroomScriptPlan plan = parser.parse(read(example));
            assertThat(plan.legacy()).as(example).isFalse();
            assertThat(plan.stage(ClassroomState.WAITING).has("expected")).as(example).isFalse();
        }
    }

    private String read(String relativePath) throws Exception {
        Path contract = Path.of(System.getProperty("user.dir"), "..", "..", "contracts", relativePath)
            .toAbsolutePath()
            .normalize();
        assertThat(Files.isRegularFile(contract)).as("Missing contract file %s", contract).isTrue();
        return Files.readString(contract, StandardCharsets.UTF_8);
    }
}
