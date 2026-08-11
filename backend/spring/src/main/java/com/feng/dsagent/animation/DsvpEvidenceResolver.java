package com.feng.dsagent.animation;

import com.feng.dsagent.common.ApiException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Resolves DSVP context from rows the current user is allowed to reference. */
@Component
final class DsvpEvidenceResolver {

    private final JdbcTemplate jdbc;

    DsvpEvidenceResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    DsvpEvidenceResolution resolve(
        long userId,
        Set<String> roles,
        JsonNode request,
        DsvpEvidenceSource source
    ) {
        DsvpEvidenceContext context = DsvpEvidenceContext.from(request);
        validateSourceType(context, source);

        Candidate classroom = context.classroomSessionId() == null
            ? null
            : classroom(userId, context.classroomSessionId());
        Candidate presentation = context.presentationPageId() == null
            ? null
            : presentation(userId, roles, context.presentationPageId(), context.presentationId());
        String explicit = publishedChapter(context.chapterId());

        if (classroom == null && context.classroomSessionId() != null) forbidden();
        if (presentation == null && context.presentationPageId() != null) forbidden();

        if (classroom != null && presentation != null && !classroom.chapterId().equals(presentation.chapterId())) {
            conflict();
        }
        Candidate authoritative = classroom != null ? classroom : presentation;
        if (authoritative != null && explicit != null && !authoritative.chapterId().equals(explicit)) {
            conflict();
        }
        if (authoritative == null && explicit != null) {
            authoritative = new Candidate(explicit, null, "EXPLICIT_CHAPTER");
        }
        if (authoritative == null && context.sourceRef() != null) {
            authoritative = reviewedAnimation(roles, context.sourceRef());
        }
        if (authoritative == null) {
            return DsvpEvidenceResolution.preview();
        }

        String sourceRef = authoritative.sourceRef();
        if (sourceRef == null || sourceRef.isBlank()) {
            sourceRef = context.sourceRef();
        }
        if (sourceRef == null || sourceRef.isBlank()) {
            sourceRef = source.reference();
        }
        return new DsvpEvidenceResolution(authoritative.chapterId(), sourceRef, authoritative.matchSource());
    }

    private Candidate classroom(long userId, String sessionId) {
        return jdbc.query(
            """
            SELECT COALESCE(s.chapter_id_snapshot, cscript.chapter_id) AS chapter_id
            FROM classroom_sessions s
            JOIN classroom_scripts cscript ON cscript.id = s.script_id
            JOIN chapters c ON c.id = COALESCE(s.chapter_id_snapshot, cscript.chapter_id)
            WHERE s.id = ? AND s.user_id = ? AND c.status = 'PUBLISHED'
            """,
            (row, index) -> new Candidate(
                row.getString("chapter_id"),
                "classroom_session:" + sessionId,
                "CLASSROOM_SESSION"
            ),
            sessionId,
            userId
        ).stream().findFirst().orElse(null);
    }

    private Candidate presentation(long userId, Set<String> roles, String pageId, String presentationId) {
        return jdbc.query(
            """
            SELECT m.id AS presentation_id, m.chapter_id, p.source_ref, r.license_scope
            FROM presentation_pages p
            JOIN presentation_manifests m ON m.id = p.manifest_id
            JOIN chapters c ON c.id = m.chapter_id
            LEFT JOIN resources r ON r.id = m.resource_id
            WHERE p.id = ? AND p.review_status IN ('PUBLISHED', 'VERIFIED')
              AND m.review_status IN ('PUBLISHED', 'VERIFIED')
              AND c.status = 'PUBLISHED'
              AND (r.id IS NULL OR r.review_status IN ('PUBLISHED', 'VERIFIED'))
            """,
            (row, index) -> {
                String actualPresentationId = row.getString("presentation_id");
                if (presentationId != null && !presentationId.equals(actualPresentationId)) {
                    conflict();
                }
                String license = row.getString("license_scope");
                if ("TEAM_ONLY".equalsIgnoreCase(license)
                    && !(roles.contains("TEACHER") || roles.contains("ADMIN"))) {
                    forbidden();
                }
                if ("CLASSROOM_ONLY".equalsIgnoreCase(license) && userId <= 0) forbidden();
                return new Candidate(
                    row.getString("chapter_id"),
                    row.getString("source_ref"),
                    "PRESENTATION_PAGE"
                );
            },
            pageId
        ).stream().findFirst().orElse(null);
    }

    private String publishedChapter(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) return null;
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM chapters WHERE id = ? AND status = 'PUBLISHED'",
            Integer.class,
            chapterId
        );
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DSVP_CHAPTER_INVALID", "章节不存在或尚未发布");
        }
        return chapterId;
    }

    private Candidate reviewedAnimation(Set<String> roles, String sourceRef) {
        return jdbc.query(
            """
            SELECT r.id, r.chapter_id, r.license_scope
            FROM resources r
            JOIN chapters c ON c.id = r.chapter_id
            WHERE r.id = ? AND r.resource_type = 'ANIMATION'
              AND r.review_status IN ('PUBLISHED', 'VERIFIED') AND c.status = 'PUBLISHED'
            """,
            (row, index) -> {
                String license = row.getString("license_scope");
                if ("TEAM_ONLY".equalsIgnoreCase(license)
                    && !(roles.contains("TEACHER") || roles.contains("ADMIN"))) {
                    forbidden();
                }
                return new Candidate(
                    row.getString("chapter_id"),
                    row.getString("id"),
                    "ANIMATION_DEFINITION"
                );
            },
            sourceRef
        ).stream().findFirst().orElse(null);
    }

    private void validateSourceType(DsvpEvidenceContext context, DsvpEvidenceSource source) {
        if (context.sourceType() != null && !source.type().equalsIgnoreCase(context.sourceType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DSVP_SOURCE_TYPE_INVALID", "来源类型与调用通道不匹配");
        }
    }

    private void forbidden() {
        throw new ApiException(HttpStatus.FORBIDDEN, "DSVP_SOURCE_FORBIDDEN", "无法访问所请求的学习来源");
    }

    private void conflict() {
        throw new ApiException(HttpStatus.CONFLICT, "DSVP_CHAPTER_CONFLICT", "学习来源与章节上下文冲突");
    }

    private record Candidate(String chapterId, String sourceRef, String matchSource) {
    }
}
