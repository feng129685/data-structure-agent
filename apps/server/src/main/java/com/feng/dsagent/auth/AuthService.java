package com.feng.dsagent.auth;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.JwtTokenService;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final VerificationCodeManager codes;
    private final PasswordEncoder passwords;
    private final JwtTokenService tokens;
    private final RolePolicy roles;
    private final String dummyHash;

    public AuthService(
        UserRepository users,
        VerificationCodeManager codes,
        PasswordEncoder passwords,
        JwtTokenService tokens,
        RolePolicy roles
    ) {
        this.users = users;
        this.codes = codes;
        this.passwords = passwords;
        this.tokens = tokens;
        this.roles = roles;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    public VerificationCodeDelivery requestCode(String email, String purpose) {
        return codes.request(email, purpose);
    }

    @Transactional
    public AuthSession register(String email, String code, String password) {
        String normalizedEmail = normalizeEmail(email);
        codes.consume(normalizedEmail, "register", code);
        if (users.findByEmail(normalizedEmail).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "AUTH_EMAIL_REGISTERED", "该邮箱已注册，请直接登录");
        }
        try {
            UserAccount user = users.create(normalizedEmail, passwords.encode(password), roles.rolesFor(normalizedEmail));
            return session(user);
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "AUTH_EMAIL_REGISTERED", "该邮箱已注册，请直接登录");
        }
    }

    public AuthSession login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        UserAccount user = users.findByEmail(normalizedEmail).orElse(null);
        String storedHash = user == null ? dummyHash : user.passwordHash();
        boolean matches = passwords.matches(password == null ? "" : password, storedHash);
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "邮箱或密码错误");
        }
        return session(user);
    }

    @Transactional
    public AuthSession resetPassword(String email, String code, String password) {
        String normalizedEmail = normalizeEmail(email);
        codes.consume(normalizedEmail, "reset", code);
        UserAccount user = users.findByEmail(normalizedEmail)
            .orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "AUTH_RESET_INVALID",
                "无法重置该账号的密码"
            ));
        users.updatePassword(user.id(), passwords.encode(password));
        return session(user);
    }

    public UserView user(long userId) {
        return users.findById(userId)
            .map(UserAccount::toView)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_INVALID", "登录状态已失效"));
    }

    private AuthSession session(UserAccount user) {
        return new AuthSession(tokens.issue(user.id(), user.email(), user.roles()), user.toView());
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_EMAIL_INVALID", "请输入有效的邮箱地址");
        }
        return normalized;
    }
}
