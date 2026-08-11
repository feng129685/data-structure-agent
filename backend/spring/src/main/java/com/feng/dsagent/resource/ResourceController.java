package com.feng.dsagent.resource;

import com.feng.dsagent.security.AuthenticatedUser;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class ResourceController {

    private final ResourceService resources;
    private final ResourceContentService content;

    public ResourceController(ResourceService resources, ResourceContentService content) {
        this.resources = resources;
        this.content = content;
    }

    @GetMapping("/chapters")
    List<ChapterView> chapters() {
        return resources.chapters();
    }

    @GetMapping("/chapters/{chapterId}/resources")
    List<ResourceView> resources(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String chapterId
    ) {
        return resources.resources(chapterId, user);
    }

    @GetMapping("/resources/{id}")
    ResourceView resource(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return resources.resource(id, user);
    }

    @GetMapping("/resources/{id}/content")
    ResponseEntity<Resource> content(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        ResourceContent payload = content.content(id, user);
        ContentDisposition disposition = payload.inline()
            ? ContentDisposition.inline().filename(payload.filename(), java.nio.charset.StandardCharsets.UTF_8).build()
            : ContentDisposition.attachment().filename(payload.filename(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
            .contentType(payload.mediaType())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(payload.resource());
    }
}
