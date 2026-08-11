package com.feng.dsagent.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceContentServiceTest {

    @TempDir
    Path resourceRoot;

    @Test
    void servesAReviewedPdfInsideTheConfiguredResourceRoot() throws Exception {
        Path file = resourceRoot.resolve("03-stack-queue/stack.pdf");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "%PDF-1.7 sample");
        ResourceContentService service = service(new ResourceAsset(
            view("stack-pdf"),
            "03-stack-queue/stack.pdf"
        ));

        ResourceContent content = service.content("stack-pdf", null);

        assertThat(content.filename()).isEqualTo("stack.pdf");
        assertThat(content.mediaType().toString()).isEqualTo("application/pdf");
        assertThat(content.inline()).isTrue();
        try (var input = content.resource().getInputStream()) {
            assertThat(new String(input.readAllBytes())).isEqualTo("%PDF-1.7 sample");
        }
    }

    @Test
    void rejectsPathsThatEscapeTheConfiguredResourceRoot() throws Exception {
        Path outside = resourceRoot.getParent().resolve("outside-private.txt");
        Files.writeString(outside, "private");
        ResourceContentService service = service(new ResourceAsset(view("escape"), "../outside-private.txt"));

        assertThatThrownBy(() -> service.content("escape", null))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.code()).isEqualTo("RESOURCE_CONTENT_NOT_FOUND");
                assertThat(error.getMessage()).doesNotContain("outside-private");
            });
    }

    @Test
    void hidesAssetsWithoutPublishedMetadata() {
        ResourceContentService service = service(null);

        assertThatThrownBy(() -> service.content("missing", null))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND")
            );
    }

    private ResourceContentService service(ResourceAsset asset) {
        return new ResourceContentService(new FakeResourceRepository(asset), new ResourceProperties(resourceRoot.toString()));
    }

    private ResourceView view(String id) {
        return new ResourceView(
            id,
            "03-stack-queue",
            "PDF",
            "栈与队列讲义",
            "审核后资料",
            "课程组",
            "1.0",
            "PUBLISHED",
            "PUBLIC",
            "/api/v1/resources/" + id + "/content"
        );
    }

    private static final class FakeResourceRepository implements ResourceRepository {
        private final ResourceAsset asset;

        private FakeResourceRepository(ResourceAsset asset) {
            this.asset = asset;
        }

        @Override
        public List<ChapterView> findPublishedChapters() {
            return List.of();
        }

        @Override
        public List<ResourceAsset> findPublishedByChapterId(String chapterId, ResourceAudience audience) {
            return asset == null ? List.of() : List.of(asset);
        }

        @Override
        public Optional<ResourceAsset> findPublishedById(String id, ResourceAudience audience) {
            return asset != null && asset.view().id().equals(id) ? Optional.of(asset) : Optional.empty();
        }
    }
}
