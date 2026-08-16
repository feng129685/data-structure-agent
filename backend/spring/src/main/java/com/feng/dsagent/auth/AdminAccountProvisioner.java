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

    private ApiException emailConflict() {
        return new ApiException(HttpStatus.CONFLICT, "AUTH_EMAIL_REGISTERED", "该邮箱已注册，请直接登录");
    }

    private ApiException usernameConflict() {
        return new ApiException(HttpStatus.CONFLICT, "AUTH_USERNAME_REGISTERED", "该用户名已被使用");
    }
}
