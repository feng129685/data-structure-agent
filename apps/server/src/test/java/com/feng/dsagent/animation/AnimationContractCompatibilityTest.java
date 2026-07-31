package com.feng.dsagent.animation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AnimationContractCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesEveryFieldRequiredByTheExistingFrontendRenderer() throws Exception {
        AnimationDefinition definition = new AnimationDefinition(
            true,
            "stack",
            "入栈演示",
            "观察新元素如何成为栈顶",
            List.of(1, 2),
            List.of(new AnimationStep(
                "push",
                "压入 3",
                "元素 3 进入栈顶",
                3,
                null,
                null,
                null,
                null,
                null,
                null
            ))
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(definition));

        assertThat(json.path("animation").asBoolean()).isTrue();
        assertThat(json.path("description").asText()).isEqualTo("观察新元素如何成为栈顶");
        assertThat(json.path("initial").isArray()).isTrue();
        assertThat(json.path("steps").get(0).path("label").asText()).isEqualTo("压入 3");
        assertThat(new AnimationValidator().validate(definition).valid()).isTrue();
    }
}
