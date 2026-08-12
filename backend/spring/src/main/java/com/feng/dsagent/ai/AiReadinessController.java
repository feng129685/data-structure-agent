package com.feng.dsagent.ai;

import com.feng.dsagent.knowledge.KnowledgeAudience;
import com.feng.dsagent.security.AuthenticatedUser;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiReadinessController {

    private final AiReadinessService readiness;

    public AiReadinessController(AiReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/readiness")
    AiReadinessService.Readiness current(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(required = false) @Size(min = 1, max = 64) @Pattern(regexp = ".*\\S.*") String operation,
        @RequestParam(required = false) @Size(min = 1, max = 64) @Pattern(regexp = ".*\\S.*") String chapterId,
        @RequestParam(required = false) @Size(min = 1, max = 4000) @Pattern(regexp = ".*\\S.*") String prompt
    ) {
        return readiness.current(
            KnowledgeAudience.from(user),
            user == null ? null : user.userId(),
            operation,
            chapterId,
            prompt
        );
    }
}
