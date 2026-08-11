package com.feng.dsagent.security;

import com.feng.dsagent.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService tokens;
    private final SecurityProperties properties;
    private final UserRepository users;
    private final NodeCompatibilityTokenVerifier nodeCompatibilityTokens;
    private final NodeCompatibilityUserResolver nodeCompatibilityUsers;

    public JwtAuthenticationFilter(
        JwtTokenService tokens,
        SecurityProperties properties,
        UserRepository users,
        NodeCompatibilityTokenVerifier nodeCompatibilityTokens,
        NodeCompatibilityUserResolver nodeCompatibilityUsers
    ) {
        this.tokens = tokens;
        this.properties = properties;
        this.users = users;
        this.nodeCompatibilityTokens = nodeCompatibilityTokens;
        this.nodeCompatibilityUsers = nodeCompatibilityUsers;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        RequestToken token = token(request);
        if (token.value() != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthenticatedUser signedUser = tokens.verify(token.value());
                // Role and active-state changes take effect immediately instead of
                // waiting for the JWT TTL. The signed token identifies the account;
                // authorization always comes from the current database record.
                AuthenticatedUser user = users.findById(signedUser.userId())
                    .map(account -> new AuthenticatedUser(account.id(), account.email(), account.roles()))
                    .orElseThrow(() -> new InvalidTokenException("account is no longer active"));
                authenticate(user, token.value());
            } catch (InvalidTokenException standardTokenRejected) {
                if (!token.fromBearerHeader() || !isNodeCompatibilityEndpoint(request) || !nodeCompatibilityTokens.enabled()) {
                    SecurityContextHolder.clearContext();
                } else {
                    try {
                        AuthenticatedUser user = nodeCompatibilityUsers.resolve(nodeCompatibilityTokens.verify(token.value()));
                        authenticate(user, token.value());
                    } catch (InvalidTokenException compatibilityTokenRejected) {
                        SecurityContextHolder.clearContext();
                    }
                }
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(AuthenticatedUser user, String token) {
        var authorities = user.roles().stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, token, authorities)
        );
    }

    private boolean isNodeCompatibilityEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if ("GET".equals(method) && "/api/v1/learning/progress".equals(path)) return true;
        if ("POST".equals(method) && "/api/v1/learning/events".equals(path)) return true;
        if ("POST".equals(method) && "/api/v1/animations/simulate".equals(path)) return true;
        if (!"POST".equals(method)
            || !path.startsWith("/api/v1/animations/")
            || !path.endsWith("/observations")) {
            return false;
        }
        String recordId = path.substring("/api/v1/animations/".length(), path.length() - "/observations".length());
        return recordId.length() <= 64 && recordId.matches("[A-Za-z0-9_-]+");
    }

    private RequestToken token(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return new RequestToken(authorization.substring(7).trim(), true);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return new RequestToken(null, false);
        }
        String cookieToken = Arrays.stream(cookies)
            .filter(cookie -> properties.cookieName().equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
        return new RequestToken(cookieToken, false);
    }

    private record RequestToken(String value, boolean fromBearerHeader) {
    }
}
