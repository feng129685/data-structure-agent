package com.feng.dsagent.review;

import com.feng.dsagent.common.RequestIdFilter;
import com.feng.dsagent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reviews")
@PreAuthorize("hasRole('ADMIN')")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    ReviewPage<ReviewItemView> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String search
    ) {
        return reviews.list(page, size, type, status, search);
    }

    @GetMapping("/{type}/{id}")
    ReviewDetailView detail(@PathVariable String type, @PathVariable String id) {
        return reviews.detail(type, id);
    }

    @PatchMapping("/{type}/{id}/status")
    ReviewItemView updateStatus(
        @AuthenticationPrincipal AuthenticatedUser actor,
        @PathVariable String type,
        @PathVariable String id,
        @Valid @RequestBody ReviewStatusRequest request,
        HttpServletRequest servletRequest
    ) {
        return reviews.updateStatus(actor, type, id, request.status(), request.note(), requestId(servletRequest));
    }

    @GetMapping("/{type}/{id}/history")
    java.util.List<ReviewHistoryView> history(@PathVariable String type, @PathVariable String id) {
        return reviews.history(type, id);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "" : value.toString();
    }

    public record ReviewStatusRequest(
        @NotBlank @Size(max = 32) String status,
        @Size(max = 500) String note
    ) {
    }
}
