package com.feng.dsagent.resource;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.security.JwtTokenService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ResourceContentApiIntegrationTest {

    private static final Path CONTENT_ROOT = createContentRoot();
    private static final long STUDENT_ID = 97001L;
    private static final long TEACHER_ID = 97002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @DynamicPropertySource
    static void resourceDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.resources.directory", () -> CONTENT_ROOT.toString());
    }

    @BeforeEach
    void clearData() {
        jdbc.update("DELETE FROM resources WHERE id LIKE 'resource-content-api%'");
        jdbc.update("DELETE FROM chapters WHERE id LIKE 'resource-content-api%'");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (?, ?)", STUDENT_ID, TEACHER_ID);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", STUDENT_ID, TEACHER_ID);
        seedUser(STUDENT_ID, "student-content@example.com", "STUDENT");
        seedUser(TEACHER_ID, "teacher-content@example.com", "STUDENT", "TEACHER");
    }

    @Test
    void deliversPublishedFilesThroughAStableUrlWithoutExposingTheirPath() throws Exception {
        Path file = CONTENT_ROOT.resolve("03-stack-queue/stack.pdf");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "%PDF-1.7 sample");
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, file_path,
                source_name, version_label, review_status, license_scope
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "resource-content-api",
            "03-stack-queue",
            "PDF",
            "栈与队列讲义",
            "审核后资料",
            "03-stack-queue/stack.pdf",
            "课程组",
            "1.0",
            "PUBLISHED",
            "PUBLIC"
        );

        mockMvc.perform(get("/api/v1/resources/resource-content-api"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentUrl").value("/api/v1/resources/resource-content-api/content"))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString(CONTENT_ROOT.toString()))));

        mockMvc.perform(get("/api/v1/resources/resource-content-api/content"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", containsString("inline")))
            .andExpect(content().string("%PDF-1.7 sample"));
    }

    @Test
    void enforcesClassroomAndTeamFileLicenses() throws Exception {
        Path file = CONTENT_ROOT.resolve("03-stack-queue/restricted.pdf");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "%PDF-1.7 restricted");
        insertRestricted("resource-content-api-classroom", "CLASSROOM_ONLY", "03-stack-queue/restricted.pdf");
        insertRestricted("resource-content-api-team", "TEAM_ONLY", "03-stack-queue/restricted.pdf");

        String student = tokens.issue(STUDENT_ID, "student-content@example.com", Set.of("STUDENT"));
        String teacher = tokens.issue(TEACHER_ID, "teacher-content@example.com", Set.of("STUDENT", "TEACHER"));

        mockMvc.perform(get("/api/v1/resources/resource-content-api-classroom/content"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/resources/resource-content-api-classroom/content")
                .header("Authorization", "Bearer " + student))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/resources/resource-content-api-team/content")
                .header("Authorization", "Bearer " + student))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/resources/resource-content-api-team/content")
                .header("Authorization", "Bearer " + teacher))
            .andExpect(status().isOk());
    }

    @Test
    void doesNotDeliverAFileFromADraftChapter() throws Exception {
        String chapterId = "resource-content-api-draft-chapter";
        String resourceId = "resource-content-api-draft-file";
        Path file = CONTENT_ROOT.resolve("draft-chapter/file.pdf");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "%PDF-1.7 draft chapter");
        jdbc.update(
            "INSERT INTO chapters (id, chapter_number, title, summary, status) VALUES (?, ?, ?, ?, ?)",
            chapterId,
            25,
            "Draft chapter",
            "Not ready",
            "DRAFT"
        );
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, file_path,
                source_name, version_label, review_status, license_scope
            ) VALUES (?, ?, 'PDF', 'Draft chapter file', 'Reviewed file', ?, 'Course team', '1.0', 'PUBLISHED', 'PUBLIC')
            """,
            resourceId,
            chapterId,
            "draft-chapter/file.pdf"
        );

        mockMvc.perform(get("/api/v1/resources/{id}/content", resourceId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private void insertRestricted(String id, String licenseScope, String filePath) {
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, file_path,
                source_name, version_label, review_status, license_scope
            ) VALUES (?, '03-stack-queue', 'PDF', '受限资料', '审核后资料', ?, '课程组', '1.0', 'PUBLISHED', ?)
            """,
            id,
            filePath,
            licenseScope
        );
    }

    private void seedUser(long id, String email, String... roles) {
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'hash')", id, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        }
    }

    private static Path createContentRoot() {
        try {
            return Files.createTempDirectory("ds-agent-resource-api-");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
