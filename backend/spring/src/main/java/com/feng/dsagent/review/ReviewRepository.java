package com.feng.dsagent.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ReviewRepository {

    private final JdbcTemplate jdbc;

    ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ReviewPage<ReviewEntity> list(ReviewQuery query) {
        List<Object> parameters = new ArrayList<>();
        String where = where(query, parameters);
        String candidates = candidates(query.types());
        long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM (" + candidates + ") q" + where,
            Long.class,
            parameters.toArray()
        );
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(query.size());
        pageParameters.add((long) query.page() * query.size());
        List<ReviewEntity> items = jdbc.query(
            "SELECT type, content_id, title, review_status, chapter_id, version_label, source_name, source_path, "
                + "parent_id, source_type, source_ref, content_hash, updated_at FROM ("
                + candidates + ") q" + where + " ORDER BY updated_at DESC, content_id ASC LIMIT ? OFFSET ?",
            (row, index) -> entity(row),
            pageParameters.toArray()
        );
        return new ReviewPage<>(items, query.page(), query.size(), total);
    }

    Optional<ReviewEntity> find(ReviewType type, String id) {
        return queryOne("SELECT * FROM (" + queryFor(type) + ") q WHERE content_id = ?", id);
    }

    Optional<ReviewEntity> findPresentationPageBySourceRef(String sourceRef) {
        return queryOne(
            "SELECT * FROM (" + queryFor(ReviewType.PRESENTATION_PAGE) + ") q WHERE source_ref = ?",
            sourceRef
        );
    }

    Optional<ReviewEntity> lock(ReviewType type, String id) {
        return queryOne("SELECT * FROM (" + queryFor(type) + ") q WHERE content_id = ? FOR UPDATE", id);
    }

    Optional<ReviewSourceEntity> chapter(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<ReviewSourceEntity> rows = jdbc.query(
            "SELECT id, title, status FROM chapters WHERE id = ?",
            (row, index) -> new ReviewSourceEntity(
                "CHAPTER",
                row.getString("id"),
                row.getString("title"),
                row.getString("status")
            ),
            id
        );
        return rows.stream().findFirst();
    }

    void updateStatus(ReviewType type, String id, String status) {
        String statement = switch (type) {
            case RESOURCE -> "UPDATE resources SET review_status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            case KNOWLEDGE_CHUNK -> "UPDATE knowledge_chunks SET review_status = ? WHERE id = ?";
            case PRESENTATION_MANIFEST -> "UPDATE presentation_manifests SET review_status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            case PRESENTATION_PAGE -> "UPDATE presentation_pages SET review_status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            case DSVP_REQUEST_SNAPSHOT -> "UPDATE dsvp_request_snapshots SET review_status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        };
        jdbc.update(statement, status, id);
    }

    void appendReviewEvent(
        ReviewType type,
        String id,
        String previousStatus,
        String nextStatus,
        String note,
        long reviewerUserId,
        String requestId
    ) {
        jdbc.update(
            """
                INSERT INTO content_review_events (
                    content_type, content_id, previous_status, next_status, note, reviewer_user_id, request_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            type.name(), id, previousStatus, nextStatus, note, reviewerUserId, requestId
        );
    }

    void appendAdminAudit(
        long actorUserId,
        ReviewType type,
        String id,
        String result,
        String requestId,
        String beforeSummary,
        String afterSummary
    ) {
        jdbc.update(
            """
                INSERT INTO admin_audit_events (
                    actor_user_id, action, target_type, target_id, result, request_id, before_summary, after_summary
                ) VALUES (?, 'REVIEW_STATUS_CHANGED', ?, ?, ?, ?, ?, ?)
                """,
            actorUserId, type.name(), id, result, requestId, beforeSummary, afterSummary
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void appendRejectedAdminAudit(
        long actorUserId,
        ReviewType type,
        String id,
        String requestId,
        String beforeSummary,
        String afterSummary
    ) {
        appendAdminAudit(actorUserId, type, id, "REJECTED", requestId, beforeSummary, afterSummary);
    }

    List<ReviewHistoryView> history(ReviewType type, String id) {
        return jdbc.query(
            """
                SELECT id, previous_status, next_status, note, reviewer_user_id, request_id, created_at
                FROM content_review_events
                WHERE content_type = ? AND content_id = ?
                ORDER BY created_at DESC, id DESC
                """,
            (row, index) -> new ReviewHistoryView(
                row.getLong("id"),
                row.getString("previous_status"),
                row.getString("next_status"),
                row.getString("note"),
                nullableLong(row, "reviewer_user_id"),
                row.getString("request_id"),
                instant(row.getTimestamp("created_at"))
            ),
            type.name(), id
        );
    }

    private Optional<ReviewEntity> queryOne(String sql, String id) {
        List<ReviewEntity> rows = jdbc.query(sql, (row, index) -> entity(row), id);
        return rows.stream().findFirst();
    }

    private String where(ReviewQuery query, List<Object> parameters) {
        List<String> clauses = new ArrayList<>();
        if (query.status() != null) {
            clauses.add("review_status = ?");
            parameters.add(query.status());
        }
        if (query.search() != null) {
            clauses.add("LOWER(title) LIKE ?");
            parameters.add("%" + query.search().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private String candidates(List<ReviewType> types) {
        return types.stream().map(this::queryFor).collect(java.util.stream.Collectors.joining(" UNION ALL "));
    }

    private String queryFor(ReviewType type) {
        return switch (type) {
            case RESOURCE -> """
                SELECT 'RESOURCE' AS type, r.id AS content_id, r.title, r.review_status, r.chapter_id,
                       r.version_label, r.source_name, r.file_path AS source_path, NULL AS parent_id,
                       NULL AS source_type, NULL AS source_ref, NULL AS content_hash, r.updated_at
                FROM resources r
                """;
            case KNOWLEDGE_CHUNK -> """
                SELECT 'KNOWLEDGE_CHUNK' AS type, k.id AS content_id, k.title, k.review_status, k.chapter_id,
                       '' AS version_label, '' AS source_name, k.source_path, k.resource_id AS parent_id,
                       NULL AS source_type, NULL AS source_ref, NULL AS content_hash, k.created_at AS updated_at
                FROM knowledge_chunks k
                """;
            case PRESENTATION_MANIFEST -> """
                SELECT 'PRESENTATION_MANIFEST' AS type, p.id AS content_id, p.title, p.review_status, p.chapter_id,
                       p.version_label, p.source_name, p.source_path, p.resource_id AS parent_id,
                       NULL AS source_type, NULL AS source_ref, p.content_hash, p.updated_at
                FROM presentation_manifests p
                """;
            case PRESENTATION_PAGE -> """
                SELECT 'PRESENTATION_PAGE' AS type, p.id AS content_id, p.title, p.review_status, m.chapter_id,
                       p.version_label, m.source_name, p.source_ref AS source_path, p.manifest_id AS parent_id,
                       NULL AS source_type, p.source_ref, p.content_hash, p.updated_at
                FROM presentation_pages p
                INNER JOIN presentation_manifests m ON m.id = p.manifest_id
                """;
            case DSVP_REQUEST_SNAPSHOT -> """
                SELECT 'DSVP_REQUEST_SNAPSHOT' AS type, d.id AS content_id, CONCAT('DSVP ', d.id) AS title,
                       d.review_status, m.chapter_id, d.version_label, d.source_type AS source_name,
                       d.source_ref AS source_path, d.animation_record_id AS parent_id, d.source_type, d.source_ref,
                       d.request_hash AS content_hash, d.updated_at
                FROM dsvp_request_snapshots d
                INNER JOIN presentation_pages p ON d.source_type = 'PPT' AND p.source_ref = d.source_ref
                INNER JOIN presentation_manifests m ON m.id = p.manifest_id
                """;
        };
    }

    private ReviewEntity entity(ResultSet row) throws SQLException {
        return new ReviewEntity(
            ReviewType.valueOf(row.getString("type")),
            row.getString("content_id"),
            row.getString("title"),
            row.getString("review_status"),
            row.getString("chapter_id"),
            row.getString("version_label"),
            row.getString("source_name"),
            row.getString("source_path"),
            row.getString("parent_id"),
            row.getString("source_type"),
            row.getString("source_ref"),
            row.getString("content_hash"),
            instant(row.getTimestamp("updated_at"))
        );
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

record ReviewEntity(
    ReviewType type,
    String id,
    String title,
    String status,
    String chapterId,
    String versionLabel,
    String sourceName,
    String sourcePath,
    String parentId,
    String sourceType,
    String sourceRef,
    String contentHash,
    Instant updatedAt
) {
}

record ReviewSourceEntity(String type, String id, String title, String status) {
}

record ReviewQuery(int page, int size, List<ReviewType> types, String status, String search) {
}
