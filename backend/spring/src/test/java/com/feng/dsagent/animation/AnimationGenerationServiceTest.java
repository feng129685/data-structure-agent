package com.feng.dsagent.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class AnimationGenerationServiceTest {

    private final FakeModelClient model = new FakeModelClient();
    private final FakeAnimationRepository repository = new FakeAnimationRepository();
    private final AnimationGenerationService service = new AnimationGenerationService(
        model,
        new AnimationValidator(),
        repository,
        new ObjectMapper()
    );

    @Test
    void parsesFencedModelJsonAndReturnsValidatedGuestAnimationWithoutPersisting() {
        model.response = """
            ```json
            {
              "type":"stack",
              "title":"入栈与出栈",
              "steps":[
                {"op":"push","note":"元素 1 进入栈顶","value":1},
                {"op":"pop","note":"栈顶元素离开","value":null}
              ]
            }
            ```
            """;

        AnimationGenerationResponse response = service.generate(
            new AnimationGenerationCommand("演示 1 入栈后再出栈", "stack", "03-stack-queue"),
            null
        );

        assertThat(response.definition().type()).isEqualTo("stack");
        assertThat(response.definition().steps()).hasSize(2);
        assertThat(response.persisted()).isFalse();
        assertThat(response.recordId()).isNull();
        assertThat(repository.saved).isEmpty();
        assertThat(model.request.messages().getFirst().content())
            .contains("只返回 JSON")
            .contains("禁止 HTML")
            .contains("stack");
    }

    @Test
    void rejectsUnsupportedOperationReturnedByModel() {
        model.response = """
            {"type":"stack","title":"危险动画","steps":[
              {"op":"executeScript","note":"运行脚本","value":"alert(1)"}
            ]}
            """;

        assertThatThrownBy(() -> service.generate(
            new AnimationGenerationCommand("演示栈", "stack", null),
            null
        )).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(error.code()).isEqualTo("ANIMATION_MODEL_INVALID");
        });
    }

    @Test
    void persistsValidatedAnimationForAuthenticatedUser() {
        model.response = """
            {"type":"queue","title":"队列操作","steps":[
              {"op":"enqueue","note":"元素 A 从队尾入队","value":"A"}
            ]}
            """;

        AnimationGenerationResponse response = service.generate(
            new AnimationGenerationCommand("演示入队", "queue", "03-stack-queue"),
            9L
        );

        assertThat(response.persisted()).isTrue();
        assertThat(response.recordId()).isEqualTo("animation-1");
        assertThat(repository.saved).singleElement().satisfies(saved -> {
            assertThat(saved.userId()).isEqualTo(9);
            assertThat(saved.definition().type()).isEqualTo("queue");
        });
    }

    @Test
    void rejectsUnknownChapterBeforeCallingModel() {
        repository.chapterExists = false;

        assertThatThrownBy(() -> service.generate(
            new AnimationGenerationCommand("演示未知章节", "stack", "99-missing"),
            9L
        )).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error.code()).isEqualTo("ANIMATION_CHAPTER_INVALID");
        });

        assertThat(model.request).isNull();
        assertThat(repository.saved).isEmpty();
    }

    private static final class FakeModelClient implements ModelClient {
        private String response;
        private ModelRequest request;

        @Override
        public ModelResponse complete(ModelRequest request) {
            this.request = request;
            return new ModelResponse(response);
        }

        @Override
        public void stream(ModelRequest request, ModelStreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAnimationRepository implements AnimationRepository {
        private final List<SavedAnimation> saved = new ArrayList<>();
        private boolean chapterExists = true;

        @Override
        public boolean isPublishedChapter(String chapterId) {
            return chapterExists;
        }

        @Override
        public String save(long userId, String chapterId, AnimationDefinition definition, String payloadJson) {
            saved.add(new SavedAnimation(userId, definition));
            return "animation-" + saved.size();
        }
    }

    private record SavedAnimation(long userId, AnimationDefinition definition) {
    }
}
