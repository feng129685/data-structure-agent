package com.feng.dsagent.resource;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.AuthenticatedUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
final class ResourceService {

    private final ResourceRepository resources;

    ResourceService(ResourceRepository resources) {
        this.resources = resources;
    }

    List<ChapterView> chapters() {
        return resources.findPublishedChapters();
    }

    List<ResourceView> resources(String chapterId, AuthenticatedUser user) {
        return resources.findPublishedByChapterId(chapterId, ResourceAudience.from(user)).stream()
            .map(ResourceAsset::view)
            .toList();
    }

    ResourceView resource(String id, AuthenticatedUser user) {
        return resources.findPublishedById(id, ResourceAudience.from(user)).map(ResourceAsset::view)
            .orElseThrow(() -> new ApiException(
            HttpStatus.NOT_FOUND,
            "RESOURCE_NOT_FOUND",
            "资料不存在"
        ));
    }
}
