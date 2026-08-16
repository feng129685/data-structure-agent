package com.feng.dsagent.mail;

import com.feng.dsagent.auth.AuthProperties;
import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.AuthenticatedUser;
import com.feng.dsagent.security.SecurityProperties;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailConfigService {

    static final long CONFIGURATION_ID = MailConfigRepository.CONFIGURATION_ID;
    static final String DEFAULT_SITE_NAME = "数据结构智能体";
    static final String DEFAULT_SUBJECT = "[{{site_name}}] 邮箱验证码";
    static final String DEFAULT_TEMPLATE = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>{{site_name}} 邮箱验证码</title>
        </head>
        <body style="margin:0;padding:24px;background:#eef0eb;color:#181a18;font-family:Georgia,Times New Roman,serif;">
          <div style="display:none;max-height:0;overflow:hidden;opacity:0;">你的 {{site_name}} 登录验证码是 {{code}}</div>
          <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="width:100%;background:#eef0eb;border-collapse:collapse;">
            <tr><td align="center">
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="width:100%;max-width:640px;overflow:hidden;border:1px solid #cfd3ce;border-radius:8px;background:#fbfbf8;border-collapse:separate;">
                <tr><td style="padding:26px 30px;border-bottom:1px solid #d7dad5;">
                  <table role="presentation" cellspacing="0" cellpadding="0" style="border-collapse:collapse;"><tr>
                    <td width="64" style="width:64px;vertical-align:middle;"><div style="display:block;width:52px;height:52px;border-radius:14px;background:#181a18;color:#fbfbf8;text-align:center;font:700 18px/52px Georgia,serif;">ds</div></td>
                    <td style="vertical-align:middle;"><div style="color:#181a18;font-family:Bookman Old Style,Georgia,serif;font-size:23px;font-weight:700;line-height:1.1;">{{site_name}}</div><div style="margin-top:5px;color:#646a65;font-size:12px;line-height:1.2;">{{site_name}} ACCOUNT VERIFICATION</div></td>
                  </tr></table>
                </td></tr>
                <tr><td style="padding:34px 30px 30px;">
                  <div style="color:#555c56;font-size:12px;font-weight:700;line-height:1.4;">邮箱安全验证</div>
                  <h1 style="margin:9px 0 14px;color:#181a18;font-size:30px;font-weight:500;line-height:1.2;">验证你的邮箱</h1>
                  <p style="margin:0 0 22px;color:#4f5650;font-size:15px;line-height:1.75;">你正在登录 {{site_name}}。请使用下面的验证码完成验证：</p>
                  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="width:100%;border-radius:6px;background:#181a18;border-collapse:separate;"><tr><td align="center" style="padding:24px 18px;"><div style="margin-bottom:9px;color:#aeb4ae;font-size:11px;line-height:1.2;">VERIFICATION CODE</div><div style="color:#fbfbf8;font-family:Bookman Old Style,Georgia,serif;font-size:36px;font-weight:700;line-height:1;letter-spacing:7px;">{{code}}</div></td></tr></table>
                  <p style="margin:22px 0 0;color:#343a35;font-size:14px;line-height:1.7;">验证码将在 <strong>{{expires_minutes}} 分钟</strong>后失效，请勿转发给他人。</p>
                  <p style="margin:8px 0 0;color:#737a74;font-size:13px;line-height:1.7;">如果不是你本人操作，可以忽略这封邮件。</p>
                </td></tr>
                <tr><td style="padding:18px 30px;border-top:1px solid #d7dad5;background:#f1f2ee;color:#747b75;font-size:12px;line-height:1.6;">此邮件由 {{site_name}} 自动发送，请勿直接回复。</td></tr>
              </table>
            </td></tr>
          </table>
        </body>
        </html>
        """;
    private static final Pattern HOST_LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final java.util.Set<Integer> ALLOWED_PORTS = java.util.Set.of(25, 465, 587, 2525);

    private final MailConfigMasterKeySource masterKeySource;
    private final MailConfigRepository repository;
    private final MailConfigCrypto crypto;
    private final MailTransport transport;
    private final AuthProperties authProperties;
    private final SecurityProperties securityProperties;
    private final JavaMailSender fallbackMailSender;

    public MailConfigService(
        MailConfigMasterKeySource masterKeySource,
        MailConfigRepository repository,
        MailConfigCrypto crypto,
        MailTransport transport,
        AuthProperties authProperties,
        SecurityProperties securityProperties,
        JavaMailSender fallbackMailSender
    ) {
        this.masterKeySource = masterKeySource;
        this.repository = repository;
        this.crypto = crypto;
        this.transport = transport;
        this.authProperties = authProperties;
        this.securityProperties = securityProperties;
        this.fallbackMailSender = fallbackMailSender;
    }

    public MailConfigController.MailConfigCapabilityView capability() {
        Optional<SecretKey> key = masterKeySource.masterKey();
        if (key.isEmpty()) {
            return new MailConfigController.MailConfigCapabilityView(false, "MASTER_KEY_UNAVAILABLE", null);
        }
        return new MailConfigController.MailConfigCapabilityView(
            true,
            null,
            repository.find().map(this::view).orElseGet(() -> view(null))
        );
    }

    public MailPolicy policy() {
        return repository.find().map(this::policy).orElseGet(this::fallbackPolicy);
    }

    public boolean deliveryEnabled() {
        return repository.find().map(MailConfigRepository.StoredMailConfig::enabled).orElse(authProperties.mailEnabled());
    }

    @Transactional
    public MailConfigController.MailConfigView update(
        MailConfigController.MailConfigRequest request,
        AuthenticatedUser actor,
        String requestId
    ) {
        SecretKey key = requiredMasterKey();
        MailConfigRepository.StoredMailConfig existing = repository.find().orElse(null);
        NormalizedDraft draft = normalize(request, existing);
        String ciphertext = passwordCiphertextForSave(draft, existing, key);
        if (draft.enabled() && !draft.smtpUsername().isBlank() && (ciphertext == null || ciphertext.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_SMTP_PASSWORD_REQUIRED", "启用 SMTP 用户认证时必须填写密码");
        }
        MailConfigRepository.StoredMailConfig before = existing;
        MailConfigRepository.StoredMailConfig after = repository.save(new MailConfigRepository.StoredMailConfig(
            CONFIGURATION_ID,
            draft.siteName(), draft.enabled(), draft.smtpHost(), draft.smtpPort(), draft.securityMode().name(),
            draft.smtpUsername(), ciphertext, draft.fromEmail(), draft.fromName(), draft.connectionTimeoutSeconds(),
            draft.verificationTtlMinutes(), draft.resendIntervalSeconds(), draft.sessionTtlDays(),
            draft.verificationSubject(), draft.verificationTemplateHtml(), null, null, Instant.now()
        ));
        repository.appendAuditEvent(
            actor.userId(), "MAIL_CONFIG_UPDATED", Long.toString(CONFIGURATION_ID), "SUCCESS", safeRequestId(requestId),
            summary(before), summary(after)
        );
        return view(after);
    }

    public MailConfigController.MailConnectionTestView testConnection(
        MailConfigController.MailConfigRequest request,
        AuthenticatedUser actor,
        String requestId
    ) {
        MailConfigRepository.StoredMailConfig existing = repository.find().orElse(null);
        NormalizedDraft draft = normalize(request, existing);
        String password = resolvePasswordForDraft(draft, existing, false);
        String code = "CONNECTION_FAILED";
        boolean connected = false;
        try {
            transport.testConnection(connection(draft), password);
            connected = true;
            code = "CONNECTION_OK";
        } catch (RuntimeException ignored) {
            // Network and SMTP provider details must never cross the API boundary.
        }
        if (existing != null) {
            repository.recordConnectionTest(code);
        }
        repository.appendAuditEvent(
            actor.userId(), "MAIL_CONFIG_CONNECTION_TESTED", Long.toString(CONFIGURATION_ID),
            connected ? "SUCCESS" : "FAILED", safeRequestId(requestId), summary(existing),
            "connected=" + connected + ";unsaved=true;credentialRedacted=true"
        );
        return new MailConfigController.MailConnectionTestView(connected, code);
    }

    public MailConfigController.TestMailView sendTestMail(
        MailConfigController.TestMailRequest request,
        AuthenticatedUser actor,
        String requestId
    ) {
        String recipient = normalizeEmail(request.recipient(), "MAIL_TEST_RECIPIENT_INVALID");
        if (!recipient.equalsIgnoreCase(actor.email())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MAIL_TEST_RECIPIENT_FORBIDDEN", "测试邮件只能发送到当前管理员邮箱");
        }
        MailConfigRepository.StoredMailConfig existing = repository.find().orElse(null);
        NormalizedDraft draft = normalize(request.config(), existing);
        String password = resolvePasswordForDraft(draft, existing, false);
        String subject = MailTemplateRenderer.render(
            draft.verificationSubject(), draft.siteName(), "123456", draft.verificationTtlMinutes()
        );
        String html = MailTemplateRenderer.renderHtml(
            draft.verificationTemplateHtml(), draft.siteName(), "123456", draft.verificationTtlMinutes()
        );
        try {
            transport.send(connection(draft), password, recipient, subject, html);
        } catch (RuntimeException ignored) {
            repository.appendAuditEvent(
                actor.userId(), "MAIL_TEST_SENT", Long.toString(CONFIGURATION_ID), "FAILED", safeRequestId(requestId),
                summary(existing), "sent=false;unsaved=true;credentialRedacted=true"
            );
            return new MailConfigController.TestMailView(false, "TEST_EMAIL_FAILED");
        }
        repository.appendAuditEvent(
            actor.userId(), "MAIL_TEST_SENT", Long.toString(CONFIGURATION_ID), "SUCCESS", safeRequestId(requestId),
            summary(existing), "sent=true;unsaved=true;recipient=actor;credentialRedacted=true"
        );
        return new MailConfigController.TestMailView(true, "TEST_EMAIL_SENT");
    }

    public void sendVerification(String email, String code, String purpose) {
        MailConfigRepository.StoredMailConfig stored = repository.find().orElse(null);
        if (stored != null && stored.enabled()) {
            SecretKey key = requiredMasterKey();
            String password = decryptPassword(stored, key);
            MailPolicy policy = policy(stored);
            String subject = MailTemplateRenderer.render(policy.verificationSubject(), policy.siteName(), code, minutes(policy.verificationTtl()));
            String html = MailTemplateRenderer.renderHtml(policy.verificationTemplateHtml(), policy.siteName(), code, minutes(policy.verificationTtl()));
            try {
                transport.send(connection(stored), password, normalizeEmail(email, "AUTH_EMAIL_INVALID"), subject, html);
            } catch (RuntimeException error) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_MAIL_DELIVERY_FAILED", "验证码邮件暂时无法发送");
            }
            return;
        }
        if (!authProperties.mailEnabled()) {
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(authProperties.mailFrom());
            message.setTo(email);
            message.setSubject("数据结构智能体邮箱验证码");
            message.setText("你的验证码是 " + code + "，请在 " + minutes(authProperties.verificationTtl()) + " 分钟内使用。用途：" + purpose + "。");
            fallbackMailSender.send(message);
        } catch (RuntimeException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_MAIL_DELIVERY_FAILED", "验证码邮件暂时无法发送");
        }
    }

    private NormalizedDraft normalize(MailConfigController.MailConfigRequest request, MailConfigRepository.StoredMailConfig existing) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_CONFIG_INVALID", "邮件配置不能为空");
        }
        String siteName = requiredText(request.siteName(), 128, "MAIL_SITE_NAME_INVALID", "站点名称不能为空");
        String host = normalizeHost(request.smtpHost(), request.enabled());
        int port = request.smtpPort() == null ? (existing == null ? 465 : existing.smtpPort()) : request.smtpPort();
        if (port < 1 || port > 65535 || (request.enabled() && !ALLOWED_PORTS.contains(port))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_SMTP_PORT_INVALID", "SMTP 端口仅允许 25、465、587 或 2525");
        }
        SecurityMode mode = parseSecurityMode(request.securityMode());
        String username = normalizeText(request.smtpUsername(), 320);
        String fromEmail = normalizeEmailOptional(request.fromEmail());
        if (request.enabled() && fromEmail.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_FROM_REQUIRED", "启用邮件发送时必须填写发件人邮箱");
        }
        String fromName = requiredText(request.fromName(), 128, "MAIL_FROM_NAME_INVALID", "发件人名称不能为空");
        int timeout = bounded(request.connectionTimeoutSeconds(), 1, 30, "MAIL_TIMEOUT_INVALID", "连接超时必须在 1 到 30 秒之间");
        int ttl = bounded(request.verificationTtlMinutes(), 1, 60, "MAIL_VERIFICATION_TTL_INVALID", "验证码有效期必须在 1 到 60 分钟之间");
        int resend = bounded(request.resendIntervalSeconds(), 5, 3600, "MAIL_RESEND_INTERVAL_INVALID", "重发间隔必须在 5 到 3600 秒之间");
        int session = bounded(request.sessionTtlDays(), 1, 90, "MAIL_SESSION_TTL_INVALID", "登录会话周期必须在 1 到 90 天之间");
        String subject = requiredText(request.verificationSubject(), 300, "MAIL_SUBJECT_INVALID", "邮件主题不能为空");
        String template = requiredText(request.verificationTemplateHtml(), 100_000, "MAIL_TEMPLATE_INVALID", "邮件模板不能为空");
        MailTemplateRenderer.validate(subject, template);
        return new NormalizedDraft(
            siteName, request.enabled(), host, port, mode, username,
            normalizeText(request.smtpPassword(), 4096), request.clearSmtpPassword(), fromEmail, fromName,
            timeout, ttl, resend, session, subject, template
        );
    }

    private String passwordCiphertextForSave(NormalizedDraft draft, MailConfigRepository.StoredMailConfig existing, SecretKey key) {
        if (draft.clearSmtpPassword()) {
            if (!draft.smtpPassword().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_PASSWORD_CLEAR_CONFLICT", "清除密码时不能同时填写新密码");
            }
            return null;
        }
        String password = draft.smtpPassword();
        if (password.isBlank() && existing != null && existing.smtpPasswordCiphertext() != null) {
            if (sameConnectionIdentity(draft, existing)) {
                return existing.smtpPasswordCiphertext();
            }
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MAIL_SMTP_PASSWORD_REQUIRED",
                "SMTP 连接信息变更后必须重新填写密码"
            );
        }
        if (password.isBlank()) {
            return null;
        }
        return crypto.encrypt(password, key, binding(draft));
    }

    private String resolvePasswordForDraft(NormalizedDraft draft, MailConfigRepository.StoredMailConfig existing, boolean saving) {
        if (draft.clearSmtpPassword()) {
            return "";
        }
        if (!draft.smtpPassword().isBlank()) {
            return draft.smtpPassword();
        }
        if (existing != null && existing.smtpPasswordCiphertext() != null
            && sameConnectionIdentity(draft, existing)) {
            return decryptPassword(existing, requiredMasterKey());
        }
        if (!draft.smtpUsername().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_SMTP_PASSWORD_REQUIRED", "当前 SMTP 用户名对应的密码未填写");
        }
        return "";
    }

    private boolean sameConnectionIdentity(NormalizedDraft draft, MailConfigRepository.StoredMailConfig existing) {
        return draft.smtpHost().equals(existing.smtpHost())
            && draft.smtpPort() == existing.smtpPort()
            && draft.securityMode().name().equals(existing.securityMode())
            && draft.smtpUsername().equals(existing.smtpUsername());
    }

    private String decryptPassword(MailConfigRepository.StoredMailConfig stored, SecretKey key) {
        if (stored.smtpPasswordCiphertext() == null || stored.smtpPasswordCiphertext().isBlank()) {
            return "";
        }
        try {
            return crypto.decrypt(stored.smtpPasswordCiphertext(), key, new MailConfigKeyBinding(
                stored.id(), stored.smtpHost(), stored.smtpPort(), stored.securityMode(), stored.smtpUsername()
            ));
        } catch (MailConfigCrypto.CryptoFailure error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_CONFIG_UNAVAILABLE", "邮件配置密钥不可用");
        }
    }

    private MailConnection connection(NormalizedDraft draft) {
        return new MailConnection(
            draft.siteName(), draft.smtpHost(), draft.smtpPort(), draft.securityMode(), draft.smtpUsername(),
            draft.fromEmail(), draft.fromName(), draft.connectionTimeoutSeconds()
        );
    }

    private MailConnection connection(MailConfigRepository.StoredMailConfig stored) {
        return new MailConnection(
            stored.siteName(), stored.smtpHost(), stored.smtpPort(), parseSecurityMode(stored.securityMode()),
            stored.smtpUsername(), stored.fromEmail(), stored.fromName(), stored.connectionTimeoutSeconds()
        );
    }

    private MailPolicy policy(MailConfigRepository.StoredMailConfig stored) {
        return new MailPolicy(
            Duration.ofMinutes(stored.verificationTtlMinutes()),
            Duration.ofSeconds(stored.resendIntervalSeconds()),
            Duration.ofDays(stored.sessionTtlDays()),
            stored.siteName(), stored.verificationSubject(), stored.verificationTemplateHtml()
        );
    }

    private MailPolicy fallbackPolicy() {
        return new MailPolicy(
            authProperties.verificationTtl(), Duration.ofSeconds(60), securityProperties.tokenTtl(),
            DEFAULT_SITE_NAME, DEFAULT_SUBJECT, DEFAULT_TEMPLATE
        );
    }

    private MailConfigController.MailConfigView view(MailConfigRepository.StoredMailConfig stored) {
        if (stored == null) {
            return new MailConfigController.MailConfigView(
                DEFAULT_SITE_NAME, false, "", 465, "SSL", "", false, authProperties.mailFrom(),
                DEFAULT_SITE_NAME, 12, minutes(authProperties.verificationTtl()), 60,
                days(securityProperties.tokenTtl()), DEFAULT_SUBJECT, DEFAULT_TEMPLATE, null, null, null
            );
        }
        return new MailConfigController.MailConfigView(
            stored.siteName(), stored.enabled(), stored.smtpHost(), stored.smtpPort(), stored.securityMode(),
            stored.smtpUsername(), stored.smtpPasswordCiphertext() != null && !stored.smtpPasswordCiphertext().isBlank(),
            stored.fromEmail(), stored.fromName(), stored.connectionTimeoutSeconds(), stored.verificationTtlMinutes(),
            stored.resendIntervalSeconds(), stored.sessionTtlDays(), stored.verificationSubject(),
            stored.verificationTemplateHtml(), stored.lastConnectionTestStatus(), stored.lastConnectionTestedAt(), stored.updatedAt()
        );
    }

    private String summary(MailConfigRepository.StoredMailConfig stored) {
        if (stored == null) {
            return "state=UNCONFIGURED";
        }
        return "configured=true;enabled=" + stored.enabled()
            + ";host=" + stored.smtpHost()
            + ";port=" + stored.smtpPort()
            + ";securityMode=" + stored.securityMode()
            + ";usernameConfigured=" + !stored.smtpUsername().isBlank()
            + ";passwordConfigured=" + (stored.smtpPasswordCiphertext() != null && !stored.smtpPasswordCiphertext().isBlank())
            + ";verificationTtlMinutes=" + stored.verificationTtlMinutes()
            + ";resendIntervalSeconds=" + stored.resendIntervalSeconds()
            + ";sessionTtlDays=" + stored.sessionTtlDays();
    }

    private SecretKey requiredMasterKey() {
        return masterKeySource.masterKey().orElseThrow(() -> new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE, "MAIL_CONFIG_UNAVAILABLE", "邮件配置密钥未配置"
        ));
    }

    private MailConfigKeyBinding binding(NormalizedDraft draft) {
        return new MailConfigKeyBinding(CONFIGURATION_ID, draft.smtpHost(), draft.smtpPort(), draft.securityMode().name(), draft.smtpUsername());
    }

    private String normalizeHost(String value, boolean required) {
        String host = normalizeText(value, 253).toLowerCase(Locale.ROOT);
        if (host.isBlank() && !required) {
            return "";
        }
        if (host.isBlank() || host.contains("://") || host.contains("/") || host.equals("localhost")
            || host.endsWith(".local") || host.endsWith(".internal") || !HOST_LABEL.matcher(host).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_SMTP_HOST_INVALID", "SMTP 主机必须是公开域名");
        }
        try {
            host = IDN.toASCII(host);
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isForbiddenAddress(address)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_SMTP_HOST_FORBIDDEN", "SMTP 主机不能指向本机或私有网络");
                }
            }
        } catch (java.net.UnknownHostException ignored) {
            // DNS may be temporarily unavailable while saving. The connection test remains authoritative.
        }
        return host;
    }

    private boolean isForbiddenAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()
            || (bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127);
    }

    private SecurityMode parseSecurityMode(String value) {
        try {
            return SecurityMode.valueOf(normalizeText(value, 16).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_SECURITY_MODE_INVALID", "安全模式必须是 NONE、STARTTLS 或 SSL");
        }
    }

    private String normalizeEmailOptional(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return normalizeEmail(value, "MAIL_FROM_EMAIL_INVALID");
    }

    private String normalizeEmail(String value, String code) {
        String email = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length() > 254) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, "请输入有效的邮箱地址");
        }
        return email;
    }

    private String requiredText(String value, int max, String code, String message) {
        String normalized = normalizeText(value, max);
        if (normalized.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        return normalized;
    }

    private String normalizeText(String value, int max) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        if (normalized.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAIL_CONFIG_FIELD_TOO_LONG", "邮件配置字段过长");
        }
        return normalized;
    }

    private int bounded(Integer value, int min, int max, String code, String message) {
        int number = value == null ? min : value;
        if (number < min || number > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        return number;
    }

    private int minutes(Duration duration) {
        return Math.max(1, (int) duration.toMinutes());
    }

    private int days(Duration duration) {
        return Math.max(1, (int) duration.toDays());
    }

    private int minutes(Duration duration, int fallback) {
        return duration == null ? fallback : minutes(duration);
    }

    private String safeRequestId(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), 128));
    }

    private record NormalizedDraft(
        String siteName,
        boolean enabled,
        String smtpHost,
        int smtpPort,
        SecurityMode securityMode,
        String smtpUsername,
        String smtpPassword,
        boolean clearSmtpPassword,
        String fromEmail,
        String fromName,
        int connectionTimeoutSeconds,
        int verificationTtlMinutes,
        int resendIntervalSeconds,
        int sessionTtlDays,
        String verificationSubject,
        String verificationTemplateHtml
    ) {
    }
}
