package com.feng.dsagent.animation;

import tools.jackson.databind.JsonNode;

/** Normalized, client-supplied hints that are resolved against server-owned rows. */
record DsvpEvidenceContext(
    String chapterId,
    String lessonId,
    String presentationId,
    String presentationPageId,
    String classroomSessionId,
    String sourceType,
    String sourceRef
) {

    static DsvpEvidenceContext from(JsonNode request) {
        JsonNode context = request == null ? null : request.path("context");
        if (context == null || !context.isObject()) {
            return new DsvpEvidenceContext(null, null, null, null, null, null, null);
        }
        return new DsvpEvidenceContext(
            text(context, "chapter_id"),
            text(context, "lesson_id"),
            text(context, "presentation_id"),
            text(context, "presentation_page_id"),
            text(context, "classroom_session_id"),
            text(context, "source_type"),
            text(context, "source_ref")
        );
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }
}
