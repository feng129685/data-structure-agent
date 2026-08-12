package com.feng.dsagent.aiquota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.knowledge.KnowledgeEligibilityChanged;
import com.feng.dsagent.knowledge.KnowledgeIndexRefreshService;
import com.feng.dsagent.knowledge.KnowledgeSearchService;
import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelClientException;
import com.feng.dsagent.model.ModelErrorCode;
import com.feng.dsagent.model.ModelMessage;
import com.feng.dsagent.model.ModelRequest;
import com.feng.dsagent.model.ModelResponse;
import com.feng.dsagent.model.ModelStreamHandler;
import com.feng.dsagent.security.JwtTokenService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "app.model.provider=fake",
    "app.model.api-key=test-only-key",
    "app.model.base-url=https://model.example/v1",
    "app.model.name=fake-model",
    "app.ai-quota.daily-token-quota=10000",
    "app.ai-quota.maximum-concurrent-requests=1",
    "app.ai-quota.reservation-ttl=PT2M"
})
@AutoConfigureMockMvc
class AiQuotaExecutionApiIntegrationTest {

    private static final long USER_ID = 9101L;
    private static final String ANIMATION_JSON = """
        {"animation":true,"type":"stack","title":"Stack push","description":"Observe the stack top",
        "initial":[],"steps":[{"op":"push","label":"Push 1","note":"1 becomes the stack top",
        "value":1,"index":null,"node":null,"i":null,"j":null,"key":null,"val":null}]}
        """;

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

    @MockitoBean(name = "persistedModelConfigClient")
    private ModelClient model;

    @Autowired
    private AiQuotaExecution execution;

    @BeforeEach
    void prepareData() {
        jdbc.update("DELETE FROM ai_quota_reservations");
        jdbc.update("DELETE FROM ai_quota_buckets");
        jdbc.update("DELETE FROM ai_quota_user_concurrency");
        jdbc.update("DELETE FROM model_configurations");
        jdbc.update("DELETE FROM knowledge_chunks WHERE id = ?", "quota-api-stack");
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID);
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)", USER_ID, "quota-api@example.com", "hash");
        jdbc.update(
            """
                INSERT INTO knowledge_chunks (
                    id, chapter_id, title, content, source_path, page_label, review_status, license_scope
                ) VALUES (?, ?, ?, ?, ?, ?, 'VERIFIED', 'PUBLIC')
                """,
            "quota-api-stack",
            "03-stack-queue",
            "Stack definition",
            "A stack is a last-in, first-out linear structure. Push and pop operate at the stack top.",
            "fixtures/knowledge/stack.md",
            "page 52"
        );
        knowledgeIndex.refreshAfterEligibilityChange(new KnowledgeEligibilityChanged());
    }

    @AfterEach
    void resetKnowledge() {
        knowledge.replace(List.of());
        jdbc.update("DELETE FROM knowledge_chunks WHERE id = ?", "quota-api-stack");
    }

    @Test
    void authenticatedFormalCallsReserveAndSettleReportedOrFailureUsageThroughTheSharedBoundary() throws Exception {
        when(model.complete(any())).thenReturn(new ModelResponse("A stack is last-in, first-out.", 21L));

        mockMvc.perform(post("/api/v1/chat")
                .header("Authorization", bearer())
                .header("X-Request-ID", "quota-chat-success")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt":"stack","chapterId":"03-stack-queue","history":[]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("A stack is last-in, first-out."));

        mockMvc.perform(get("/api/v1/ai/readiness")
                .header("Authorization", bearer())
                .param("chapterId", "03-stack-queue")
                .param("prompt", "stack"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.remainingDailyTokenQuota").value(9979))
            .andExpect(jsonPath("$.quotaStatus").value("AVAILABLE"))
            .andExpect(jsonPath("$.allowFormalGeneration").value(true));

        when(model.complete(any())).thenThrow(new ModelClientException(ModelErrorCode.MODEL_REQUEST_TIMEOUT, 7L));

        mockMvc.perform(post("/api/v1/code/analyze")
                .header("Authorization", bearer())
                .header("X-Request-ID", "quota-code-failure")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"language":"python","code":"print(1)","stdin":"","stdout":"1",
                    "stderr":"","status":"success"}
                    """))
            .andExpect(status().isGatewayTimeout())
            .andExpect(jsonPath("$.code").value("MODEL_REQUEST_TIMEOUT"));

        doReturn(new ModelResponse(ANIMATION_JSON, 11L)).when(model).complete(any());

        mockMvc.perform(post("/api/v1/animations/generate")
                .header("Authorization", bearer())
                .header("X-Request-ID", "quota-animation-success")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt":"Demonstrate a stack push","preferredType":"stack","chapterId":"03-stack-queue"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.definition.type").value("stack"));

        assertThat(jdbc.queryForObject(
            "SELECT consumed_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isEqualTo(39L);
        assertThat(jdbc.queryForObject(
            "SELECT reserved_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT active_reservations FROM ai_quota_user_concurrency WHERE user_id = ?",
            Integer.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT actual_tokens FROM ai_quota_reservations WHERE status = 'FAILED'",
            Long.class
        )).isEqualTo(7L);
        assertThat(jdbc.queryForObject(
            "SELECT usage_source FROM ai_quota_reservations WHERE status = 'FAILED'",
            String.class
        )).isEqualTo("PROVIDER_REPORTED");
        verify(model, times(3)).complete(any());
    }

    @Test
    void guestFormalCallsAndAuthenticatedStreamingAreControlledQuotaBoundaries() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt":"stack","chapterId":"03-stack-queue","history":[]}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AI_QUOTA_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt":"stack","chapterId":"03-stack-queue","history":[]}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AI_QUOTA_AUTHENTICATION_REQUIRED"));

        doAnswer(invocation -> {
            ModelStreamHandler handler = invocation.getArgument(1, ModelStreamHandler.class);
            handler.onContent("Stack ");
            handler.onContent("answers");
            return null;
        }).when(model).stream(any(), any());

        MvcResult stream = mockMvc.perform(post("/api/v1/chat/stream")
                .header("Authorization", bearer())
                .header("X-Request-ID", "quota-chat-stream-success")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt":"stack","chapterId":"03-stack-queue","history":[]}
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(stream))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(completed.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(completed.getResponse().getContentAsString())
            .contains("event:sources", "event:delta", "Stack ", "answers", "event:done");
        assertThat(jdbc.queryForObject(
            "SELECT usage_source FROM ai_quota_reservations WHERE status = 'SETTLED'",
            String.class
        )).isEqualTo("RESERVATION_ESTIMATE");
        assertThat(jdbc.queryForObject(
            "SELECT reserved_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT active_reservations FROM ai_quota_user_concurrency WHERE user_id = ?",
            Integer.class,
            USER_ID
        )).isZero();
        verify(model).stream(any(), any());
    }

    @Test
    void streamingSettlesProviderReportedUsageInsteadOfTheReservationEstimate() throws Exception {
        doAnswer(invocation -> {
            ModelStreamHandler handler = invocation.getArgument(1, ModelStreamHandler.class);
            handler.onContent("Stack ");
            handler.onContent("answers");
            handler.onUsage(5_000L);
            return null;
        }).when(model).stream(any(), any());

        MvcResult stream = mockMvc.perform(post("/api/v1/chat/stream")
                .header("Authorization", bearer())
                .header("X-Request-ID", "quota-chat-stream-provider-usage")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt":"stack","chapterId":"03-stack-queue","history":[]}
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(stream))
            .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
            "SELECT actual_tokens FROM ai_quota_reservations WHERE status = 'SETTLED'",
            Long.class
        )).isEqualTo(5_000L);
        assertThat(jdbc.queryForObject(
            "SELECT estimated_tokens FROM ai_quota_reservations WHERE status = 'SETTLED'",
            Long.class
        )).isLessThan(5_000L);
        assertThat(jdbc.queryForObject(
            "SELECT usage_source FROM ai_quota_reservations WHERE status = 'SETTLED'",
            String.class
        )).isEqualTo("PROVIDER_REPORTED");
        assertThat(jdbc.queryForObject(
            "SELECT reserved_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT active_reservations FROM ai_quota_user_concurrency WHERE user_id = ?",
            Integer.class,
            USER_ID
        )).isZero();
    }

    @Test
    void upstreamStreamingFailureEmitsControlledErrorAndReleasesTheReservation() throws Exception {
        doAnswer(invocation -> {
            ModelStreamHandler handler = invocation.getArgument(1, ModelStreamHandler.class);
            handler.onContent("partial answer");
            throw new ModelClientException(ModelErrorCode.MODEL_STREAM_IDLE_TIMEOUT);
        }).when(model).stream(any(), any());

        MvcResult stream = mockMvc.perform(post("/api/v1/chat/stream")
                .header("Authorization", bearer())
                .header("X-Request-ID", "quota-chat-stream-timeout")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt":"stack","chapterId":"03-stack-queue","history":[]}
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(stream))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(completed.getResponse().getContentAsString())
            .contains("event:delta", "partial answer", "event:error", "MODEL_STREAM_IDLE_TIMEOUT");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM ai_quota_reservations",
            String.class
        )).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
            "SELECT usage_source FROM ai_quota_reservations",
            String.class
        )).isEqualTo("RESERVATION_ESTIMATE");
        assertThat(jdbc.queryForObject(
            "SELECT reserved_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT active_reservations FROM ai_quota_user_concurrency WHERE user_id = ?",
            Integer.class,
            USER_ID
        )).isZero();
    }

    @Test
    void providerUsageReportedBeforeStreamingFailureSettlesActualUsage() {
        doAnswer(invocation -> {
            ModelStreamHandler handler = invocation.getArgument(1, ModelStreamHandler.class);
            handler.onContent("partial answer");
            handler.onUsage(13L);
            throw new ModelClientException(ModelErrorCode.MODEL_STREAM_IDLE_TIMEOUT);
        }).when(model).stream(any(), any());

        assertThatThrownBy(() -> execution.stream(
            USER_ID,
            "chat",
            "quota-chat-stream-provider-failure-usage",
            new ModelRequest(List.of(new ModelMessage("user", "stack")), 0.2, 32),
            content -> { }
        )).isInstanceOfSatisfying(ModelClientException.class, error ->
            assertThat(error.code()).isEqualTo("MODEL_STREAM_IDLE_TIMEOUT")
        );

        assertThat(jdbc.queryForObject(
            "SELECT actual_tokens FROM ai_quota_reservations WHERE status = 'FAILED'",
            Long.class
        )).isEqualTo(13L);
        assertThat(jdbc.queryForObject(
            "SELECT usage_source FROM ai_quota_reservations WHERE status = 'FAILED'",
            String.class
        )).isEqualTo("PROVIDER_REPORTED");
        assertThat(jdbc.queryForObject(
            "SELECT reserved_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT active_reservations FROM ai_quota_user_concurrency WHERE user_id = ?",
            Integer.class,
            USER_ID
        )).isZero();
    }

    @Test
    void providerReportedUsageOnStreamingFailureSettlesActualUsage() {
        doThrow(new ModelClientException(ModelErrorCode.MODEL_UPSTREAM_ERROR, 23L)).when(model).stream(any(), any());

        assertThatThrownBy(() -> execution.stream(
            USER_ID,
            "chat",
            "quota-chat-stream-upstream-error-usage",
            new ModelRequest(List.of(new ModelMessage("user", "stack")), 0.2, 32),
            content -> { }
        )).isInstanceOfSatisfying(ModelClientException.class, error ->
            assertThat(error.code()).isEqualTo("MODEL_UPSTREAM_ERROR")
        );

        assertThat(jdbc.queryForObject(
            "SELECT actual_tokens FROM ai_quota_reservations WHERE status = 'FAILED'",
            Long.class
        )).isEqualTo(23L);
        assertThat(jdbc.queryForObject(
            "SELECT usage_source FROM ai_quota_reservations WHERE status = 'FAILED'",
            String.class
        )).isEqualTo("PROVIDER_REPORTED");
        assertThat(jdbc.queryForObject(
            "SELECT reserved_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT active_reservations FROM ai_quota_user_concurrency WHERE user_id = ?",
            Integer.class,
            USER_ID
        )).isZero();
    }

    @Test
    void clientDisconnectedDuringStreamingReleasesTheReservationWithAnAuditableReason() {
        doAnswer(invocation -> {
            ModelStreamHandler handler = invocation.getArgument(1, ModelStreamHandler.class);
            handler.onContent("partial answer");
            return null;
        }).when(model).stream(any(), any());

        assertThatThrownBy(() -> execution.stream(
            USER_ID,
            "chat",
            "quota-chat-stream-disconnected",
            new ModelRequest(List.of(new ModelMessage("user", "stack")), 0.2, 32),
            content -> { throw new AiStreamAbortedException(new IllegalStateException("closed")); }
        )).isInstanceOf(AiStreamAbortedException.class);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM ai_quota_reservations",
            String.class
        )).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
            "SELECT failure_code FROM ai_quota_reservations",
            String.class
        )).isEqualTo("AI_STREAM_CLIENT_DISCONNECTED");
        assertThat(jdbc.queryForObject(
            "SELECT usage_source FROM ai_quota_reservations",
            String.class
        )).isEqualTo("RESERVATION_ESTIMATE");
        assertThat(jdbc.queryForObject(
            "SELECT reserved_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT active_reservations FROM ai_quota_user_concurrency WHERE user_id = ?",
            Integer.class,
            USER_ID
        )).isZero();
    }

    @Test
    void clientDisconnectAfterProviderUsageSettlesActualUsage() {
        doAnswer(invocation -> {
            ModelStreamHandler handler = invocation.getArgument(1, ModelStreamHandler.class);
            handler.onUsage(17L);
            handler.onContent("partial answer");
            return null;
        }).when(model).stream(any(), any());

        assertThatThrownBy(() -> execution.stream(
            USER_ID,
            "chat",
            "quota-chat-stream-disconnected-provider-usage",
            new ModelRequest(List.of(new ModelMessage("user", "stack")), 0.2, 32),
            content -> { throw new AiStreamAbortedException(new IllegalStateException("closed")); }
        )).isInstanceOf(AiStreamAbortedException.class);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM ai_quota_reservations",
            String.class
        )).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
            "SELECT failure_code FROM ai_quota_reservations",
            String.class
        )).isEqualTo("AI_STREAM_CLIENT_DISCONNECTED");
        assertThat(jdbc.queryForObject(
            "SELECT actual_tokens FROM ai_quota_reservations",
            Long.class
        )).isEqualTo(17L);
        assertThat(jdbc.queryForObject(
            "SELECT usage_source FROM ai_quota_reservations",
            String.class
        )).isEqualTo("PROVIDER_REPORTED");
        assertThat(jdbc.queryForObject(
            "SELECT reserved_tokens FROM ai_quota_buckets WHERE user_id = ?",
            Long.class,
            USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT active_reservations FROM ai_quota_user_concurrency WHERE user_id = ?",
            Integer.class,
            USER_ID
        )).isZero();
    }

    private String bearer() {
        return "Bearer " + tokens.issue(USER_ID, "quota-api@example.com", Set.of("STUDENT"));
    }
}
