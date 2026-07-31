package com.feng.dsagent.animation;

import com.feng.dsagent.model.ModelFeatureRateLimiter;
import com.feng.dsagent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/animations")
public class AnimationController {

    private final AnimationGenerationService animations;
    private final AnimationObservationService observations;
    private final ModelFeatureRateLimiter rateLimiter;

    public AnimationController(
        AnimationGenerationService animations,
        AnimationObservationService observations,
        ModelFeatureRateLimiter rateLimiter
    ) {
        this.animations = animations;
        this.observations = observations;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/generate")
    AnimationGenerationResponse generate(
        @AuthenticationPrincipal AuthenticatedUser user,
        HttpServletRequest servletRequest,
        @Valid @RequestBody GenerateAnimationRequest request
    ) {
        Long userId = user == null ? null : user.userId();
        rateLimiter.check("animation", userId, servletRequest.getRemoteAddr());
        return animations.generate(
            new AnimationGenerationCommand(request.prompt(), request.preferredType(), request.chapterId()),
            userId
        );
    }

    @PostMapping("/{id}/observations")
    AnimationObservationView observation(
        @AuthenticationPrincipal AuthenticatedUser user,
        @org.springframework.web.bind.annotation.PathVariable String id,
        @Valid @RequestBody ObservationRequest request
    ) {
        return observations.record(user.userId(), id, request.observation());
    }

    public record GenerateAnimationRequest(
        @NotBlank @Size(max = 2000) String prompt,
        @Size(max = 16) String preferredType,
        @Size(max = 64) String chapterId
    ) {
    }

    public record ObservationRequest(@NotBlank @Size(max = 2000) String observation) {
    }
}
