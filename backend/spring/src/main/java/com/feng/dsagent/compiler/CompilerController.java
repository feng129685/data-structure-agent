package com.feng.dsagent.compiler;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.common.WindowRateLimiter;
import com.feng.dsagent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/code")
public final class CompilerController {

    private static final int REQUESTS_PER_MINUTE = 20;

    private final CompilerService compiler;
    private final Clock clock;
    private final CompilerConcurrencyLimiter concurrencyLimiter;
    private final WindowRateLimiter rateLimiter = new WindowRateLimiter(
        REQUESTS_PER_MINUTE,
        Duration.ofMinutes(1)
    );

    @Autowired
    public CompilerController(
        CompilerService compiler,
        Clock clock,
        CompilerConcurrencyLimiter concurrencyLimiter
    ) {
        this.compiler = compiler;
        this.clock = clock;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    CompilerController(CompilerService compiler, Clock clock) {
        this(compiler, clock, new CompilerConcurrencyLimiter(4, 1));
    }

    @PostMapping({"/runs", "/run"})
    public RunCodeResponse run(
        @RequestBody RunCodeRequest request,
        HttpServletRequest servletRequest,
        Authentication authentication
    ) {
        if (!rateLimiter.allow(clientKey(authentication, servletRequest), clock.instant())) {
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "COMPILER_RATE_LIMITED",
                "代码运行请求过于频繁，请稍后重试"
            );
        }
        Long userId = authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
            ? user.userId()
            : null;
        try (CompilerConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquire(clientKey(authentication, servletRequest))) {
            return compiler.run(request, userId);
        }
    }

    private static String clientKey(Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return "user:" + user.userId();
        }
        String remoteAddress = request.getRemoteAddr();
        return "ip:" + (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress);
    }
}
