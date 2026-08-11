package com.feng.dsagent.resource;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.AuthenticatedUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
final class ResourceContentService {

    private final ResourceRepository resources;
    private final ResourceProperties properties;

    ResourceContentService(ResourceRepository resources, ResourceProperties properties) {
        this.resources = resources;
        this.properties = properties;
    }

    ResourceContent content(String id, AuthenticatedUser user) {
        ResourceAsset asset = resources.findPublishedById(id, ResourceAudience.from(user))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资料不存在"));
        Path file = resolve(asset.filePath());
        return new ResourceContent(
            new FileSystemResource(file),
            mediaType(file),
            file.getFileName().toString(),
            inline(file)
        );
    }

    private Path resolve(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw contentNotFound();
        }
        try {
            Path root = properties.root();
            if (!Files.isDirectory(root)) {
                throw contentNotFound();
            }
            Path configured = Path.of(storedPath.trim());
            if (configured.isAbsolute()) {
                throw contentNotFound();
            }
            Path candidate = root.resolve(configured).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                throw contentNotFound();
            }
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw contentNotFound();
            }
            return realCandidate;
        } catch (ApiException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw contentNotFound();
        }
    }

    private MediaType mediaType(Path file) {
        try {
            String detected = Files.probeContentType(file);
            if (detected != null && !detected.isBlank()) {
                return MediaType.parseMediaType(detected);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Use a deterministic extension fallback when the platform does not know the file type.
        }
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".c") || name.endsWith(".py")) {
            return MediaType.TEXT_PLAIN;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private boolean inline(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".pdf") || name.endsWith(".md") || name.endsWith(".txt")
            || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private ApiException contentNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_CONTENT_NOT_FOUND", "资料内容不存在或不可访问");
    }
}
