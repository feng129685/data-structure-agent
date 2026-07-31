package com.feng.dsagent.resource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcResourceRepository implements ResourceRepository {

    private static final String RESOURCE_COLUMNS = """
        r.id AS id, r.chapter_id AS chapter_id, r.resource_type AS resource_type, r.title AS title,
        r.description AS description, r.file_path AS file_path, r.source_name AS source_name,
        r.version_label AS version_label, r.review_status AS review_status, r.license_scope AS license_scope
        """;

    private final JdbcTemplate jdbc;

    public JdbcResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChapterView> findPublishedChapters() {
        return jdbc.query(
            """
            SELECT id, chapter_number, title, summary
            FROM chapters
            WHERE status = 'PUBLISHED'
            ORDER BY chapter_number
            """,
            (row, index) -> new ChapterView(
                row.getString("id"),
                row.getInt("chapter_number"),
                row.getString("title"),
                row.getString("summary")
            )
        );
    }

    @Override
    public List<ResourceAsset> findPublishedByChapterId(String chapterId, ResourceAudience audience) {
        return jdbc.query(
            "SELECT " + RESOURCE_COLUMNS + """
                FROM resources r
                INNER JOIN chapters c ON c.id = r.chapter_id
                WHERE r.chapter_id = ? AND r.review_status = 'PUBLISHED' AND c.status = 'PUBLISHED'
                  AND (
                    r.license_scope = 'PUBLIC'
                    OR (r.license_scope = 'CLASSROOM_ONLY' AND ?)
                    OR (r.license_scope = 'TEAM_ONLY' AND ?)
                  )
                ORDER BY r.updated_at DESC, r.id
                """,
            (row, index) -> resource(row),
            chapterId,
            audience.allowClassroomOnly(),
            audience.allowTeamOnly()
        );
    }

    @Override
    public Optional<ResourceAsset> findPublishedById(String id, ResourceAudience audience) {
        List<ResourceAsset> resources = jdbc.query(
            "SELECT " + RESOURCE_COLUMNS + """
                FROM resources r
                INNER JOIN chapters c ON c.id = r.chapter_id
                WHERE r.id = ? AND r.review_status = 'PUBLISHED' AND c.status = 'PUBLISHED'
                  AND (
                    r.license_scope = 'PUBLIC'
                    OR (r.license_scope = 'CLASSROOM_ONLY' AND ?)
                    OR (r.license_scope = 'TEAM_ONLY' AND ?)
                  )
                """,
            (row, index) -> resource(row),
            id,
            audience.allowClassroomOnly(),
            audience.allowTeamOnly()
        );
        return resources.stream().findFirst();
    }

    private ResourceAsset resource(ResultSet row) throws SQLException {
        String id = row.getString("id");
        String filePath = row.getString("file_path");
        return new ResourceAsset(
            new ResourceView(
                id,
                row.getString("chapter_id"),
                row.getString("resource_type"),
                row.getString("title"),
                row.getString("description"),
                row.getString("source_name"),
                row.getString("version_label"),
                row.getString("review_status"),
                row.getString("license_scope"),
                filePath == null || filePath.isBlank() ? null : "/api/v1/resources/" + id + "/content"
            ),
            filePath
        );
    }
}
