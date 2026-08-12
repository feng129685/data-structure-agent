package com.feng.dsagent.compiler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feng.dsagent.common.ApiExceptionHandler;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CompilerControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CompilerProperties properties = new CompilerProperties(
            "http://127.0.0.1:1",
            Duration.ofSeconds(1),
            8,
            4,
            64,
            4,
            1,
            10_000,
            3_000
        );
        CompilerGateway gateway = (language, code, stdin) ->
            new CompilerExecution("success", language == SupportedLanguage.C ? "c-ok" : "python-ok", "");
        CompilerService service = new CompilerService(properties, gateway);
        CompilerController controller = new CompilerController(service, Clock.systemUTC());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void runsPythonThroughTheVersionedApi() throws Exception {
        mockMvc.perform(post("/api/v1/code/runs")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.10");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"language":"python","code":"print(1)","stdin":""}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.language").value("python"))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.stdout").value("python-ok"))
            .andExpect(jsonPath("$.stderr").value(""))
            .andExpect(jsonPath("$.durationMs").isNumber());
    }

    @Test
    void keepsTheSingularPathAsAnExplicitCompatibilityAlias() throws Exception {
        mockMvc.perform(post("/api/v1/code/run")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.11");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"language":"c","code":"main(){}","stdin":""}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.language").value("c"))
            .andExpect(jsonPath("$.stdout").value("c-ok"));
    }

    @Test
    void returnsTheUnifiedApiErrorForOversizedCode() throws Exception {
        mockMvc.perform(post("/api/v1/code/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"language":"c","code":"123456789","stdin":""}
                    """))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("COMPILER_CODE_TOO_LONG"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void rateLimitsTheTwentyFirstRequestFromTheSameClient() throws Exception {
        for (int requestNumber = 1; requestNumber <= 20; requestNumber++) {
            mockMvc.perform(post("/api/v1/code/runs")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.20");
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"language":"python","code":"print(1)","stdin":""}
                        """))
                .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/code/runs")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.20");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"language":"python","code":"print(1)","stdin":""}
                    """))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("COMPILER_RATE_LIMITED"));
    }
}
