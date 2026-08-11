package com.feng.dsagent.auth;

import com.feng.dsagent.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final SecurityProperties security;
    private final AuthRequestRateLimiter rateLimiter;

    public AuthController(AuthService auth, SecurityProperties security, AuthRequestRateLimiter rateLimiter) {
        this.auth = auth;
        this.security = security;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/request-code")
    VerificationCodeResponse requestCode(
        @Valid @RequestBody RequestCodeRequest request,
        HttpServletRequest servletRequest
    ) {
        rateLimiter.checkCodeRequest(request.email(), servletRequest.getRemoteAddr());
        // Keep the delivery result (and any test-only in-memory code) inside the
        // service boundary. Verification codes must never cross the HTTP boundary.
        return new VerificationCodeResponse(
            auth.requestCode(request.email(), request.purpose()).message()
        );
    }

    @PostMapping("/register")
    ResponseEntity<AuthResponse> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletRequest servletRequest
    ) {
        rateLimiter.checkCodeAttempt(request.email(), servletRequest.getRemoteAddr());
        return authenticated(auth.register(request.email(), request.code(), request.password()));
    }

    @PostMapping("/login")
    ResponseEntity<AuthResponse> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest servletRequest
    ) {
        rateLimiter.checkLogin(request.email(), servletRequest.getRemoteAddr());
        return authenticated(auth.login(request.email(), request.password()));
    }

    @PostMapping("/reset-password")
    ResponseEntity<AuthResponse> resetPassword(
        @Valid @RequestBody ResetPasswordRequest request,
        HttpServletRequest servletRequest
    ) {
        rateLimiter.checkCodeAttempt(request.email(), servletRequest.getRemoteAddr());
        return authenticated(auth.resetPassword(request.email(), request.code(), request.password()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        ResponseCookie cookie = ResponseCookie.from(security.cookieName(), "")
            .httpOnly(true)
            .secure(security.cookieSecure())
            .sameSite("Strict")
            .path("/")
            .maxAge(0)
            .build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    private ResponseEntity<AuthResponse> authenticated(AuthSession session) {
        ResponseCookie cookie = ResponseCookie.from(security.cookieName(), session.token())
            .httpOnly(true)
            .secure(security.cookieSecure())
            .sameSite("Strict")
            .path("/")
            .maxAge(security.tokenTtl())
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(new AuthResponse(session.token(), session.user()));
    }

    public record RequestCodeRequest(@Email @NotBlank String email, @NotBlank String purpose) {
    }

    public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 6, max = 6) String code,
        @NotBlank @Size(min = 8, max = 128) String password
    ) {
    }

    public record LoginRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(max = 128) String password
    ) {
    }

    public record ResetPasswordRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 6, max = 6) String code,
        @NotBlank @Size(min = 8, max = 128) String password
    ) {
    }

    public record AuthResponse(String token, UserView user) {
    }

    public record VerificationCodeResponse(String message) {
    }
}
