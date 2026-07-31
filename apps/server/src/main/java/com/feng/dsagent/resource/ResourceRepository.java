package com.feng.dsagent.resource;

import java.util.List;
import java.util.Optional;

interface ResourceRepository {

    List<ChapterView> findPublishedChapters();

    List<ResourceAsset> findPublishedByChapterId(String chapterId, ResourceAudience audience);

    Optional<ResourceAsset> findPublishedById(String id, ResourceAudience audience);
}
