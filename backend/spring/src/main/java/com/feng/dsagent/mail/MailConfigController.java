package com.feng.dsagent.mail;

import com.feng.dsagent.common.RequestIdFilter;
import com.feng.dsagent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/mail-config")
@PreAuthorize("hasRole('ADMIN')")
public class MailConfigController {

    private final MailConfigService service;

    public MailConfigController(MailConfigService service) {
        this.service = service;
    }

    @GetMapping
    MailConfigCapabilityView capability() {
        return service.capability();
    }

    @PutMapping
    MailConfigView update(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @Valid @RequestBody MailConfigRequest request,
        HttpServletRequest servletRequest
    ) {
        return service.update(request, actor, requestId(servletRequest));
    }

    @PostMapping("/test-connection")
    MailConnectionTestView testConnection(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @Valid @RequestBody MailConfigRequest request,
        HttpServletRequest servletRequest
    ) {
        return service.testConnection(request, actor, requestId(servletRequest));
    }

    @PostMapping("/test-email")
    TestMailView testEmail(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @Valid @RequestBody TestMailRequest request,
        HttpServletRequest servletRequest
    ) {
        return service.sendTestMail(request, actor, requestId(servletRequest));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "" : value.toString();
    }

    public record MailConfigCapabilityView(boolean available, String reason, MailConfigView configuration) {
    }

    public record MailConfigView(
        String siteName,
        boolean enabled,
        String smtpHost,
        int smtpPort,
        String securityMode,
        String smtpUsername,
        boolean smtpPasswordConfigured,
        String fromEmail,
        String fromName,
        int connectionTimeoutSeconds,
        int verificationTtlMinutes,
        int resendIntervalSeconds,
        int sessionTtlDays,
        String verificationSubject,
        String verificationTemplateHtml,
        String lastConnectionTestStatus,
        Instant lastConnectionTestedAt,
        Instant updatedAt
    ) {
    }

    public record MailConfigRequest(
        @NotBlank @Size(max = 128) String siteName,
        boolean enabled,
        @Size(max = 253) String smtpHost,
        @NotNull @Min(1) @Max(65535) Integer smtpPort,
        @NotBlank @Size(max = 16) String securityMode,
        @Size(max = 320) String smtpUsername,
        @Size(max = 4096) String smtpPassword,
        boolean clearSmtpPassword,
        @Size(max = 254) String fromEmail,
        @NotBlank @Size(max = 128) String fromName,
        @NotNull @Min(1) @Max(30) Integer connectionTimeoutSeconds,
        @NotNull @Min(1) @Max(60) Integer verificationTtlMinutes,
        @NotNull @Min(5) @Max(3600) Integer resendIntervalSeconds,
        @NotNull @Min(1) @Max(90) Integer sessionTtlDays,
        @NotBlank @Size(max = 300) String verificationSubject,
        @NotBlank @Size(max = 100000) String verificationTemplateHtml
    ) {
    }

    public record TestMailRequest(
        @Valid MailConfigRequest config,
        @NotBlank @Email @Size(max = 254) String recipient
    ) {
    }

    public record MailConnectionTestView(boolean connected, String code) {
    }

    public record TestMailView(boolean sent, String code) {
    }
}
