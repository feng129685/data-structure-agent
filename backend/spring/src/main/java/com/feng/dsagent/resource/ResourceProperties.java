package com.feng.dsagent.resource;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.resources")
public record ResourceProperties(String directory) {

    public Path root() {
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("app.resources.directory must be configured");
        }
        return Path.of(directory).toAbsolutePath().normalize();
    }
}
