package com.feng.dsagent.resource;

public record ResourceView(
    String id,
    String chapterId,
    String type,
    String title,
    String description,
    String sourceName,
    String versionLabel,
    String reviewStatus,
    String licenseScope,
    String contentUrl
) {
}
