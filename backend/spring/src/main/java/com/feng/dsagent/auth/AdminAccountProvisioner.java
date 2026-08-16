package com.feng.dsagent.auth;

import com.feng.dsagent.common.ApiException;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal-only provisioning seam for a one-time server administrator bootstrap.
 * It intentionally has no controller endpoint.
 */
@Service
public class AdminAccountProvisioner {

    private static final Set<String> ADMIN_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    private final UserRepository users;
    private final PasswordEncoder passwords;

    public AdminAccountProvisioner(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public UserAccount createAdministrator(String email, String username, String password) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedUsername = UsernamePolicy.normalizeRequired(username);
        validatePassword(password);

        if (users.findByEmail(normalizedEmail).isPresent()) {
            throw emailConflict();
        }
        if (users.findByUsername(UsernamePolicy.lookupKey(normalizedUsername)).isPresent()) {
            throw usernameConflict();
        }
        try {
            return users.create(normalizedEmail, normalizedUsername, passwords.encode(password), ADMIN_ROLES);
        } catch (DuplicateKeyException error) {
            if (users.findByUsername(UsernamePolicy.lookupKey(normalizedUsername)).isPresent()) {
                throw usernameConflict();
            }
            throw emailConflict();
        }
    }

    /**
     * Provision a bootstrap administrator, optionally repairing exactly one
     * already-existing account. Callers must gate reconciliation explicitly.
     */
    @Transactional
    public UserAccount provisionAdministrator(
        String email,
        String username,
        String password,
        boolean reconcileExisting
    ) {
        if (!reconcileExisting) {
            return createAdministrator(email, username, password);
        }

        String normalizedEmail = normalizeEmail(email);
        String normalizedUsername = UsernamePolicy.normalizeRequired(username);
        validatePassword(password);
        String usernameKey = UsernamePolicy.lookupKey(normalizedUsername);

        UserAccount emailMatch = users.findAnyByEmail(normalizedEmail).orElse(null);
        UserAccount usernameMatch = users.findAnyByUsername(usernameKey).orElse(null);
        if (emailMatch == null && usernameMatch == null) {
            try {
                return users.create(normalizedEmail, normalizedUsername, passwords.encode(password), ADMIN_ROLES);
            } catch (DuplicateKeyException error) {
                throw provisionConflict();
            }
        }
        if (emailMatch != null && usernameMatch != null && emailMatch.id() != usernameMatch.id()) {
            throw targetMismatch();
        }

        long targetUserId = emailMatch != null ? emailMatch.id() : usernameMatch.id();
        try {
            users.reconcileAdministrator(
                targetUserId,
                normalizedEmail,
                normalizedUsername,
                passwords.encode(password),
                ADMIN_ROLES
            );
        } catch (DuplicateKeyException error) {
            throw provisionConflict();
        }
        return users.findById(targetUserId)
            .orElseThrow(() -> new IllegalStateException("Administrator account is unavailable after reconciliation"));
    }

    static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_EMAIL_INVALID", "请输入有效的邮箱地址");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8 || password.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_PASSWORD_INVALID", "密码长度必须为 8 到 128 位");
        }
    }

    private ApiException targetMismatch() {
        return new ApiException(
            HttpStatus.CONFLICT,
            "AUTH_ADMIN_PROVISION_TARGET_MISMATCH",
            "管理员邮箱和用户名指向不同账户，已拒绝修复"
        );
    }

    private ApiException provisionConflict() {
        return new ApiException(
            HttpStatus.CONFLICT,
            "AUTH_ADMIN_PROVISION_CONFLICT",
            "管理员账户状态冲突，未执行修复"
        );
    }

    private ApiException emailConflict() {
        return new ApiException(HttpStatus.CONFLICT, "AUTH_EMAIL_REGISTERED", "该邮箱已注册，请直接登录");
    }

    private ApiException usernameConflict() {
        return new ApiException(HttpStatus.CONFLICT, "AUTH_USERNAME_REGISTERED", "该用户名已被使用");
    }
}
