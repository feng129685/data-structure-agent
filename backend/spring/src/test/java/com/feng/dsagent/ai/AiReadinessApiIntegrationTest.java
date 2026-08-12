package com.feng.dsagent.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.knowledge.KnowledgeEligibilityChanged;
import com.feng.dsagent.knowledge.KnowledgeIndexRefreshService;
import com.feng.dsagent.knowledge.KnowledgeSearchService;
import com.feng.dsagent.security.JwtTokenService;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AiReadinessApiIntegrationTest {

    private static final long USER_ID = 8701L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private KnowledgeSearchService knowledge;

    @Autowired
    private KnowledgeIndexRefreshService knowledgeIndex;

    @BeforeEach
    void prepareData() {
        jdbc.update("DELETE FROM model_configurations");
        jdbc.update("DELETE FROM knowledge_chunks WHERE id IN (?, ?)", "readiness-draft", "readiness-stack");
        jdbc.update("DELETE FROM resources WHERE id = ?", "readiness-stack-source");
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID);
        jdbc.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            USER_ID,
            "readiness@example.com",
            "hash"
        );
        jdbc.update(
            """
                INSERT INTO knowledge_chunks
                    (id, chapter_id, title, content, source_path, review_status, license_scope)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            "readiness-draft",
            "03-stack-queue",
            "Unreviewed stack draft",
            "This chunk is intentionally not approved.",
            "fixtures/knowledge/stack-draft.md",
            "DRAFT",
            "CLASSROOM_ONLY"
        );
        jdbc.update(
            """
                INSERT INTO resources (
                    id, chapter_id, resource_type, title, source_name, version_label, review_status, license_scope
                ) VALUES (?, ?, 'MARKDOWN', ?, ?, ?, 'VERIFIED', 'CLASSROOM_ONLY')
                """,
            "readiness-stack-source",
            "03-stack-queue",
            "栈的定义来源",
            "课程组",
            "2026.1"
        );
        jdbc.update(
            """
                INSERT INTO knowledge_chunks
                    (id, chapter_id, resource_id, title, content, source_path, review_status, license_scope)
                VALUES (?, ?, ?, ?, ?, ?, 'VERIFIED', 'CLASSROOM_ONLY')
                """,
            "readiness-stack",
            "03-stack-queue",
            "readiness-stack-source",
            "栈的定义",
            "栈是后进先出的线性结构，入栈和出栈都在栈顶进行。",
            "fixtures/knowledge/stack.md"
        );
        knowledgeIndex.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());
    }

    @AfterEach
    void resetKnowledge() {
        jdbc.update("DELETE FROM model_configurations");
        jdbc.update("DELETE FROM knowledge_chunks WHERE id IN (?, ?)", "readiness-draft", "readiness-stack");
        jdbc.update("DELETE FROM resources WHERE id = ?", "readiness-stack-source");
        knowledge.replace(java.util.List.of());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/ai/readiness"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void reportsTheSameAuthorizedEvidenceContextUsedByChat() throws Exception {
        mockMvc.perform(get("/api/v1/ai/readiness")
                .param("chapterId", "03-stack-queue")
                .param("prompt", "栈的定义")
                .header("Authorization", "Bearer " + token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modelAvailable").isBoolean())
            .andExpect(jsonPath("$.evidenceAvailable").value(true))
            .andExpect(jsonPath("$.currentContext.chapterId").value("03-stack-queue"))
            .andExpect(jsonPath("$.currentContext.queryScoped").value(true))
            .andExpect(jsonPath("$.availableKnowledgeChunkCount").value(1))
            .andExpect(jsonPath("$.availableResourceCount").value(1))
            .andExpect(jsonPath("$.availableSourceCount").value(1))
            .andExpect(jsonPath("$.excludedOrUnverifiedCount").value(1))
            .andExpect(jsonPath("$.remainingDailyTokenQuota").doesNotExist())
            .andExpect(jsonPath("$.quotaStatus").value("NOT_CONFIGURED"))
            .andExpect(jsonPath("$.allowFormalGeneration").isBoolean());
    }

    @Test
    void makesMissingQuestionEvidenceAControlledCapabilityState() throws Exception {
        mockMvc.perform(get("/api/v1/ai/readiness")
                .param("chapterId", "03-stack-queue")
                .param("prompt", "解释红黑树旋转")
                .header("Authorization", "Bearer " + token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidenceAvailable").value(false))
            .andExpect(jsonPath("$.evidenceReason").value("QUESTION_EVIDENCE_UNAVAILABLE"))
            .andExpect(jsonPath("$.allowFormalGeneration").value(false));
    }

    @Test
    void rejectsBlankOptionalParametersWithTheUnifiedValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/ai/readiness")
                .param("chapterId", "   ")
                .header("Authorization", "Bearer " + token()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("chapterId")));
    }

    @Test
    void convertsMethodValidationFailuresToTheUnifiedValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/ai/readiness")
                .param("prompt", "x".repeat(4_001))
                .header("Authorization", "Bearer " + token()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.details").isNotEmpty());
    }

    @Test
    void reportsADisabledPersistedConfigurationAsAControlledGenerationBlock() throws Exception {
        jdbc.update(
            """
                INSERT INTO model_configurations (
                    id, provider, base_url, model_name, api_key_ciphertext,
                    temperature, max_output_tokens, request_timeout_ms, retry_count, daily_token_quota, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            1L,
            "custom",
            "https://model.example/v1",
            "model-a",
            "ciphertext",
            0.2,
            1_024,
            45_000,
            0,
            4_096,
            false
        );

        mockMvc.perform(get("/api/v1/ai/readiness")
                .param("chapterId", "03-stack-queue")
                .param("prompt", "栈的定义")
                .header("Authorization", "Bearer " + token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modelAvailable").value(false))
            .andExpect(jsonPath("$.modelReason").value("PERSISTED_CONFIGURATION_DISABLED"))
            .andExpect(jsonPath("$.allowFormalGeneration").value(false))
            .andExpect(jsonPath("$.blockingReasons[0]").value("PERSISTED_CONFIGURATION_DISABLED"));
    }

    private String token() {
        return tokens.issue(USER_ID, "readiness@example.com", Set.of("STUDENT"));
    }
}
