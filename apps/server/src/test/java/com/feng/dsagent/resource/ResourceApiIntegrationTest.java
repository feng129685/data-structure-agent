package com.feng.dsagent.resource;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.security.JwtTokenService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class ResourceApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @BeforeEach
    void clearTestResources() {
        jdbc.update("DELETE FROM resources WHERE id LIKE 'resource-api-%'");
        jdbc.update("DELETE FROM chapters WHERE id LIKE 'resource-api-%'");
    }

    @Test
    void listsOnlyPublishedChaptersOrderedByChapterNumber() throws Exception {
        jdbc.update("UPDATE chapters SET status = 'DRAFT'");
        insertChapter("resource-api-third", 23, "第三章", "第三章摘要", "PUBLISHED");
        insertChapter("resource-api-hidden", 21, "隐藏章", "未发布", "DRAFT");
        insertChapter("resource-api-first", 22, "第二章", "第二章摘要", "PUBLISHED");

        mockMvc.perform(get("/api/v1/chapters"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("resource-api-first"))
            .andExpect(jsonPath("$[0].chapterNumber").value(22))
            .andExpect(jsonPath("$[0].title").value("第二章"))
            .andExpect(jsonPath("$[0].summary").value("第二章摘要"))
            .andExpect(jsonPath("$[1].id").value("resource-api-third"))
            .andExpect(jsonPath("$[1].chapterNumber").value(23));
    }

    @Test
    void listsOnlyPublishedResourcesForRequestedChapter() throws Exception {
        insertResource(
            "resource-api-published-a",
            "01-introduction",
            "PDF",
            "绪论讲义",
            "先读概念",
            "C:/private/textbook/chapter-1.pdf",
            "课程组",
            "2.0",
            "PUBLISHED"
        );
        insertResource(
            "resource-api-draft",
            "01-introduction",
            "PPT",
            "待审课件",
            "不应公开",
            "C:/private/textbook/draft.pptx",
            "课程组",
            "1.0",
            "DRAFT"
        );
        insertResource(
            "resource-api-other-chapter",
            "02-linear-list",
            "PDF",
            "线性表讲义",
            "属于另一章",
            "C:/private/textbook/chapter-2.pdf",
            "课程组",
            "1.0",
            "PUBLISHED"
        );

        mockMvc.perform(get("/api/v1/chapters/01-introduction/resources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("resource-api-published-a"))
            .andExpect(jsonPath("$[0].chapterId").value("01-introduction"))
            .andExpect(jsonPath("$[0].type").value("PDF"))
            .andExpect(jsonPath("$[0].title").value("绪论讲义"))
            .andExpect(jsonPath("$[0].description").value("先读概念"))
            .andExpect(jsonPath("$[0].sourceName").value("课程组"))
            .andExpect(jsonPath("$[0].versionLabel").value("2.0"))
            .andExpect(jsonPath("$[0].reviewStatus").value("PUBLISHED"))
            .andExpect(jsonPath("$[0].licenseScope").value("PUBLIC"));
    }

    @Test
    void resourceDetailDoesNotExposeServerFilePath() throws Exception {
        insertResource(
            "resource-api-detail",
            "03-stack-queue",
            "PDF",
            "栈与队列",
            "交互学习配套资料",
            "C:/srv/ds-agent/private/stack-and-queue.pdf",
            "数据结构课程组",
            "3.1",
            "PUBLISHED"
        );

        mockMvc.perform(get("/api/v1/resources/resource-api-detail"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("resource-api-detail"))
            .andExpect(jsonPath("$.chapterId").value("03-stack-queue"))
            .andExpect(jsonPath("$.type").value("PDF"))
            .andExpect(jsonPath("$.title").value("栈与队列"))
            .andExpect(jsonPath("$.description").value("交互学习配套资料"))
            .andExpect(jsonPath("$.sourceName").value("数据结构课程组"))
            .andExpect(jsonPath("$.versionLabel").value("3.1"))
            .andExpect(jsonPath("$.reviewStatus").value("PUBLISHED"))
            .andExpect(jsonPath("$.licenseScope").value("PUBLIC"))
            .andExpect(jsonPath("$.filePath").doesNotExist())
            .andExpect(content().string(not(containsString("C:/srv/ds-agent/private"))));
    }

    @Test
    void missingAndUnpublishedResourceReturnTheSameNotFoundResponse() throws Exception {
        insertResource(
            "resource-api-unpublished",
            "01-introduction",
            "PDF",
            "未发布资料",
            "仍在审核",
            "C:/private/unpublished.pdf",
            "课程组",
            "1.0",
            "DRAFT"
        );

        MvcResult missing = mockMvc.perform(get("/api/v1/resources/resource-api-missing")
                .header("X-Request-Id", "resource-api-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andReturn();

        MvcResult unpublished = mockMvc.perform(get("/api/v1/resources/resource-api-unpublished")
                .header("X-Request-Id", "resource-api-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andReturn();

        org.assertj.core.api.Assertions.assertThat(unpublished.getResponse().getContentAsString())
            .isEqualTo(missing.getResponse().getContentAsString());
    }

    @Test
    void publishedResourceUnderDraftChapterIsHiddenFromListAndDetail() throws Exception {
        String chapterId = "resource-api-draft-chapter";
        String resourceId = "resource-api-draft-chapter-file";
        insertChapter(chapterId, 24, "Draft chapter", "Not ready", "DRAFT");
        insertResource(
            resourceId,
            chapterId,
            "PDF",
            "Published file",
            "The file is reviewed but its chapter is not published",
            "draft-chapter/file.pdf",
            "Course team",
            "1.0",
            "PUBLISHED"
        );

        mockMvc.perform(get("/api/v1/chapters/{id}/resources", chapterId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/resources/{id}", resourceId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void filtersPublishedResourcesByLicenseScopeAndRole() throws Exception {
        insertResource(
            "resource-api-public", "03-stack-queue", "PDF", "公开讲义", "公开",
            "public.pdf", "课程组", "1.0", "PUBLISHED", "PUBLIC"
        );
        insertResource(
            "resource-api-classroom", "03-stack-queue", "PDF", "课堂讲义", "仅登录学生",
            "classroom.pdf", "课程组", "1.0", "PUBLISHED", "CLASSROOM_ONLY"
        );
        insertResource(
            "resource-api-team", "03-stack-queue", "PDF", "团队底稿", "仅教师团队",
            "team.pdf", "课程组", "1.0", "PUBLISHED", "TEAM_ONLY"
        );

        String student = tokens.issue(8601L, "student-resource@example.com", Set.of("STUDENT"));
        String teacher = tokens.issue(8602L, "teacher-resource@example.com", Set.of("STUDENT", "TEACHER"));

        mockMvc.perform(get("/api/v1/chapters/03-stack-queue/resources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("resource-api-public"));

        mockMvc.perform(get("/api/v1/chapters/03-stack-queue/resources")
                .header("Authorization", "Bearer " + student))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/chapters/03-stack-queue/resources")
                .header("Authorization", "Bearer " + teacher))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/api/v1/resources/resource-api-team")
                .header("Authorization", "Bearer " + student))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/resources/resource-api-team")
                .header("Authorization", "Bearer " + teacher))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.licenseScope").value("TEAM_ONLY"));
    }

    private void insertChapter(String id, int number, String title, String summary, String status) {
        jdbc.update(
            "INSERT INTO chapters (id, chapter_number, title, summary, status) VALUES (?, ?, ?, ?, ?)",
            id,
            number,
            title,
            summary,
            status
        );
    }

    private void insertResource(
        String id,
        String chapterId,
        String type,
        String title,
        String description,
        String filePath,
        String sourceName,
        String versionLabel,
        String reviewStatus
    ) {
        insertResource(
            id, chapterId, type, title, description, filePath, sourceName, versionLabel, reviewStatus, "PUBLIC"
        );
    }

    private void insertResource(
        String id,
        String chapterId,
        String type,
        String title,
        String description,
        String filePath,
        String sourceName,
        String versionLabel,
        String reviewStatus,
        String licenseScope
    ) {
        jdbc.update(
            """
            INSERT INTO resources (
                id, chapter_id, resource_type, title, description, file_path,
                source_name, version_label, review_status, license_scope
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            chapterId,
            type,
            title,
            description,
            filePath,
            sourceName,
            versionLabel,
            reviewStatus,
            licenseScope
        );
    }
}
