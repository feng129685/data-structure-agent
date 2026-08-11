package com.feng.dsagent.modelconfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

record ModelConfigKeyBinding(long configurationId, String provider, String baseUrl) {

    static ModelConfigKeyBinding forConfiguration(long configurationId, String provider, String baseUrl) {
        return new ModelConfigKeyBinding(
            configurationId,
            normalizeProvider(provider),
            normalizeBaseUrl(baseUrl)
        );
    }

    static ModelConfigKeyBinding forStored(ModelConfigRepository.StoredModelConfig stored) {
        return forConfiguration(stored.id(), stored.provider(), stored.baseUrl());
    }

    byte[] additionalAuthenticatedData() {
        return ("dsagent:model-config:v2\\n"
            + "configurationId=" + configurationId + "\\n"
            + "provider=" + provider + "\\n"
            + "baseUrl=" + baseUrl).getBytes(StandardCharsets.UTF_8);
    }

    private static String normalizeProvider(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.strip();
        try {
            URI uri = URI.create(normalized).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return normalized;
            }
            int port = "https".equalsIgnoreCase(scheme) && uri.getPort() == 443 ? -1 : uri.getPort();
            String path = uri.getPath();
            return new URI(
                scheme.toLowerCase(Locale.ROOT),
                null,
                host.toLowerCase(Locale.ROOT),
                port,
                path == null || path.isBlank() ? "/" : path,
                null,
                null
            ).normalize().toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            return normalized;
        }
    }
}
