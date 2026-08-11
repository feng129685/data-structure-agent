package com.feng.dsagent.modelconfig;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.feng.dsagent.common.RequestIdFilter;
import com.feng.dsagent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/model-config")
@PreAuthorize("hasRole('ADMIN')")
public class ModelConfigController {

    private final ModelConfigService service;

    public ModelConfigController(ModelConfigService service) {
        this.service = service;
    }

    @GetMapping
    ModelConfigCapabilityView capability() {
        return service.capability();
    }

    @PutMapping
    ModelConfigView update(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @Valid @RequestBody UpdateModelConfigRequest request,
        HttpServletRequest servletRequest
    ) {
        return service.update(request, actor, requestId(servletRequest));
    }

    @PostMapping("/test")
    ModelConfigConnectionTestView testConnection(
        @AuthenticationPrincipal AuthenticatedUser actor,
        HttpServletRequest servletRequest
    ) {
        return service.testConnection(actor, requestId(servletRequest));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "" : value.toString();
    }

    public record ModelConfigCapabilityView(
        boolean available,
        String reason,
        @JsonInclude(JsonInclude.Include.NON_NULL) ModelConfigView configuration
    ) {
    }

    public record ModelConfigView(
        String provider,
        String baseUrl,
        String model,
        boolean apiKeyConfigured,
        double temperature,
        int maxOutputTokens,
        long requestTimeoutMs,
        int retryCount,
        long dailyTokenQuota,
        boolean enabled,
        @JsonInclude(JsonInclude.Include.NON_NULL) String lastConnectionTestStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) java.time.Instant lastConnectionTestedAt,
        java.time.Instant updatedAt
    ) {
    }

    public record UpdateModelConfigRequest(
        @NotBlank @Size(max = 128) String provider,
        @NotBlank @Size(max = 2048) String baseUrl,
        @NotBlank @Size(max = 512) String model,
        @Size(max = 4096) String apiKey,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @Min(1) @Max(32768) Integer maxOutputTokens,
        @Min(1000) @Max(120000) Long requestTimeoutMs,
        @Min(0) @Max(5) Integer retryCount,
        @Min(0) @Max(10000000) Long dailyTokenQuota,
        Boolean enabled
    ) {
    }

    public record ModelConfigConnectionTestView(boolean connected, String code) {
    }
}
