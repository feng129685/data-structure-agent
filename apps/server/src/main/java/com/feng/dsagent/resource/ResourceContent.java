package com.feng.dsagent.resource;

import org.springframework.http.MediaType;

public record ResourceContent(
    org.springframework.core.io.Resource resource,
    MediaType mediaType,
    String filename,
    boolean inline
) {
}
