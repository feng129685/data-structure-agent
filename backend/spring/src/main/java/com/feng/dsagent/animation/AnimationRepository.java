package com.feng.dsagent.animation;

interface AnimationRepository {

    boolean isPublishedChapter(String chapterId);

    String save(long userId, String chapterId, AnimationDefinition definition, String payloadJson);
}
