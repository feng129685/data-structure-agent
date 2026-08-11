package com.feng.dsagent.animation;

import tools.jackson.databind.JsonNode;

public enum DsvpEvidenceSource {
    API("API", "api/v1/animations/simulate"),
    CLASSROOM("CLASSROOM", "classroom/dsvp"),
    PPT("PPT", "presentation/dsvp");

    private final String type;
    private final String reference;

    DsvpEvidenceSource(String type, String reference) {
        this.type = type;
        this.reference = reference;
    }

    String type() {
        return type;
    }

    String reference() {
        return reference;
    }

    static DsvpEvidenceSource fromRequest(JsonNode request) {
        JsonNode context = request == null ? null : request.path("context");
        if (context != null && context.isObject()) {
            if (hasText(context, "classroom_session_id")) return CLASSROOM;
            if (hasText(context, "presentation_page_id")) return PPT;
        }
        if (hasText(request, "classroom_session_id") || hasText(request, "classroomSessionId")) {
            return CLASSROOM;
        }
        if (hasText(request, "presentation_page_id") || hasText(request, "presentationPageId")) {
            return PPT;
        }
        return API;
    }

    private static boolean hasText(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() && !node.path(field).asText().isBlank();
    }
}
