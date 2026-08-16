package com.feng.dsagent.auth;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.common.WindowRateLimiter;
import com.feng.dsagent.mail.MailConfigService;
import com.feng.dsagent.mail.MailPolicy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultVerificationCodeManager implements VerificationCodeManager {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationCodeRepository repository;
    private final VerificationCodeSender sender;
    private final AuthProperties properties;
    private final VerificationCodeHasher hasher;
    private final Clock clock;
    private final MailConfigService mailConfig;
    private final WindowRateLimiter requestLimiter = new WindowRateLimiter(3, Duration.ofMinutes(10));

    DefaultVerificationCodeManager(
        VerificationCodeRepository repository,
        VerificationCodeSender sender,
        AuthProperties properties,
        com.feng.dsagent.security.SecurityProperties securityProperties,
        MailConfigService mailConfig,
        Clock clock
    ) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
        this.hasher = new VerificationCodeHasher(securityProperties.jwtSecret());
        this.mailConfig = mailConfig;
        this.clock = clock;
    }

    @Override
    @Transactional
    public VerificationCodeDelivery request(String email, String purpose) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedPurpose = normalizePurpose(purpose);
        Instant now = clock.instant();
        if (!requestLimiter.allow(normalizedEmail, now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_CODE_RATE_LIMITED", "验证码请求过于频繁");
        }
        MailPolicy policy = mailConfig.policy();
        if (repository.latestCreatedAt(normalizedEmail, normalizedPurpose)
            .map(createdAt -> now.isBefore(createdAt.plus(policy.resendInterval())))
            .orElse(false)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_CODE_RESEND_TOO_SOON", "验证码发送过于频繁，请稍后再试");
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        repository.save(
            normalizedEmail,
            normalizedPurpose,
            hasher.hash(normalizedEmail, normalizedPurpose, code),
            now.plus(policy.verificationTtl())
        );
        sender.send(normalizedEmail, code, normalizedPurpose);
        String developmentCode = properties.exposeDevelopmentCode() && !mailConfig.deliveryEnabled() ? code : null;
        return new VerificationCodeDelivery("如果邮箱状态符合要求，验证码将发送到该邮箱", developmentCode);
    }

    @Override
    public void consume(String email, String purpose, String code) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedPurpose = normalizePurpose(purpose);
        VerificationCodeRecord record = repository.latestActive(normalizedEmail, normalizedPurpose)
            .orElseThrow(this::invalidCode);
        Instant now = clock.instant();
        if (!now.isBefore(record.expiresAt()) || record.attempts() >= properties.maximumCodeAttempts()) {
            throw invalidCode();
        }
        String actualHash = hasher.hash(normalizedEmail, normalizedPurpose, code == null ? "" : code.trim());
        if (!hasher.matches(record.codeHash(), actualHash)) {
            repository.incrementAttemptsIfActive(record.id(), now, properties.maximumCodeAttempts());
            throw invalidCode();
        }
        if (!repository.consumeIfActive(record.id(), now, properties.maximumCodeAttempts())) {
            throw invalidCode();
        }
    }

    private ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_CODE_INVALID", "验证码无效或已过期");
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_EMAIL_INVALID", "请输入有效的邮箱地址");
        }
        return normalized;
    }

    private String normalizePurpose(String purpose) {
        String normalized = purpose == null ? "" : purpose.trim().toLowerCase(Locale.ROOT);
        if (!SetHolder.PURPOSES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_PURPOSE_INVALID", "验证码用途无效");
        }
        return normalized;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> PURPOSES = java.util.Set.of("register", "reset");
    }
}
