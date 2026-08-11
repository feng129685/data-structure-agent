package com.feng.dsagent.review;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewService {

    private static final Set<String> REVIEW_STATUSES = Set.of(
        "LEGACY_UNVERIFIED", "DRAFT", "PUBLISHED", "VERIFIED", "EXCLUDED"
    );
    private static final Set<String> USABLE_SOURCE_STATUSES = Set.of("PUBLISHED", "VERIFIED");

    private final ReviewRepository repository;

    ReviewService(ReviewRepository repository) {
        this.repository = repository;
    }

    ReviewPage<ReviewItemView> list(int page, int size, String requestedType, String requestedStatus, String search) {
        validatePage(page, size);
        List<ReviewType> types = parseTypes(requestedType);
        String status = parseOptionalStatus(requestedStatus);
        ReviewPage<ReviewEntity> reviews = repository.list(new ReviewQuery(
            page,
            size,
            types,
            status,
            normalizeOptional(search)
        ));
        return new ReviewPage<>(reviews.items().stream().map(this::view).toList(), page, size, reviews.total());
    }

    ReviewDetailView detail(String requestedType, String id) {
        ReviewEntity item = require(parseType(requestedType), normalizeId(id));
        SourceAssessment assessment = sources(item);
        return new ReviewDetailView(view(item, assessment.complete()), assessment.chain());
    }

    @Transactional
    ReviewItemView updateStatus(
        AuthenticatedUser actor,
        String requestedType,
        String id,
        String requestedStatus,
        String requestedNote,
        String requestId
    ) {
        ReviewType type = parseType(requestedType);
        String contentId = normalizeId(id);
        String nextStatus = parseRequiredStatus(requestedStatus);
        String note = normalizeOptional(requestedNote);
        ReviewEntity before = repository.lock(type, contentId).orElseThrow(() -> notFound(type, contentId));
        SourceAssessment assessment = sources(before);
        if ("VERIFIED".equals(nextStatus) && !assessment.complete()) {
            repository.appendRejectedAdminAudit(
                actor.userId(),
                type,
                contentId,
                safeRequestId(requestId),
                summary(before),
                "verification_blocked=source_chain_incomplete"
            );
            throw new ApiException(
                HttpStatus.CONFLICT,
                "ADMIN_REVIEW_SOURCE_INCOMPLETE",
                "Verified review status requires a published, complete source chain"
            );
        }
        if (before.status().equals(nextStatus)) {
            repository.appendAdminAudit(
                actor.userId(), type, contentId, "NO_CHANGE", safeRequestId(requestId), summary(before), summary(before)
            );
            return view(before, assessment.complete());
        }
        repository.updateStatus(type, contentId, nextStatus);
        ReviewEntity after = require(type, contentId);
        repository.appendReviewEvent(
            type,
            contentId,
            before.status(),
            after.status(),
            note == null ? "" : note,
            actor.userId(),
            safeRequestId(requestId)
        );
        repository.appendAdminAudit(
            actor.userId(), type, contentId, "SUCCESS", safeRequestId(requestId), summary(before), summary(after)
        );
        return view(after);
    }

    List<ReviewHistoryView> history(String requestedType, String id) {
        ReviewType type = parseType(requestedType);
        String contentId = normalizeId(id);
        require(type, contentId);
        return repository.history(type, contentId);
    }

    private ReviewItemView view(ReviewEntity entity) {
        return view(entity, sources(entity).complete());
    }

    private ReviewItemView view(ReviewEntity entity, boolean sourceComplete) {
        return new ReviewItemView(
            entity.type().name(),
            entity.id(),
            entity.title(),
            entity.status(),
            entity.chapterId(),
            entity.versionLabel(),
            sourceComplete,
            entity.updatedAt()
        );
    }

    private SourceAssessment sources(ReviewEntity item) {
        List<ReviewSourceView> chain = new ArrayList<>();
        Optional<ReviewSourceEntity> chapter = repository.chapter(item.chapterId());
        chapter.map(this::source).ifPresent(chain::add);
        boolean chapterPublished = chapter.map(value -> "PUBLISHED".equals(value.status())).orElse(false);

        return switch (item.type()) {
            case RESOURCE -> new SourceAssessment(
                chainWithDeclaredSource(chain, item),
                chapterPublished && completeMetadata(item.sourceName(), item.versionLabel())
            );
            case KNOWLEDGE_CHUNK -> sourceForKnowledge(item, chain, chapterPublished);
            case PRESENTATION_MANIFEST -> sourceForManifest(item, chain, chapterPublished);
            case PRESENTATION_PAGE -> sourceForPage(item, chain, chapterPublished);
            case DSVP_REQUEST_SNAPSHOT -> sourceForEvidence(item, chain, chapterPublished);
        };
    }

    private SourceAssessment sourceForKnowledge(
        ReviewEntity item,
        List<ReviewSourceView> chain,
        boolean chapterPublished
    ) {
        Optional<ReviewEntity> resource = item.parentId() == null || item.parentId().isBlank()
            ? Optional.empty()
            : repository.find(ReviewType.RESOURCE, item.parentId());
        resource.map(this::source).ifPresent(chain::add);
        boolean complete = chapterPublished
            && resource.filter(value -> USABLE_SOURCE_STATUSES.contains(value.status()))
                .map(this::sources)
                .map(SourceAssessment::complete)
                .orElse(false);
        return new SourceAssessment(chain, complete);
    }

    private SourceAssessment sourceForManifest(
        ReviewEntity item,
        List<ReviewSourceView> chain,
        boolean chapterPublished
    ) {
        Optional<ReviewEntity> resource = item.parentId() == null || item.parentId().isBlank()
            ? Optional.empty()
            : repository.find(ReviewType.RESOURCE, item.parentId());
        resource.map(this::source).ifPresent(chain::add);
        List<ReviewSourceView> withMetadata = chainWithDeclaredSource(chain, item);
        boolean resourceComplete = resource.isEmpty() || resource
            .filter(value -> USABLE_SOURCE_STATUSES.contains(value.status()))
            .map(this::sources)
            .map(SourceAssessment::complete)
            .orElse(false);
        return new SourceAssessment(
            withMetadata,
            chapterPublished && resourceComplete && completeMetadata(item.sourceName(), item.versionLabel())
                && nonBlank(item.sourcePath()) && nonBlank(item.contentHash())
        );
    }

    private SourceAssessment sourceForPage(
        ReviewEntity item,
        List<ReviewSourceView> chain,
        boolean chapterPublished
    ) {
        Optional<ReviewEntity> manifest = item.parentId() == null || item.parentId().isBlank()
            ? Optional.empty()
            : repository.find(ReviewType.PRESENTATION_MANIFEST, item.parentId());
        manifest.map(this::source).ifPresent(chain::add);
        boolean complete = chapterPublished
            && nonBlank(item.contentHash())
            && manifest.filter(value -> USABLE_SOURCE_STATUSES.contains(value.status()))
                .map(this::sources)
                .map(SourceAssessment::complete)
                .orElse(false);
        return new SourceAssessment(chain, complete);
    }

    private SourceAssessment sourceForEvidence(
        ReviewEntity item,
        List<ReviewSourceView> chain,
        boolean chapterPublished
    ) {
        Optional<ReviewEntity> page = item.sourceRef() == null || item.sourceRef().isBlank()
            ? Optional.empty()
            : repository.findPresentationPageBySourceRef(item.sourceRef());
        page.map(this::source).ifPresent(chain::add);
        boolean complete = chapterPublished
            && page.filter(value -> USABLE_SOURCE_STATUSES.contains(value.status()))
                .map(this::sources)
                .map(SourceAssessment::complete)
                .orElse(false);
        return new SourceAssessment(chain, complete);
    }

    private List<ReviewSourceView> chainWithDeclaredSource(List<ReviewSourceView> chain, ReviewEntity item) {
        if (!nonBlank(item.sourceName())) {
            return chain;
        }
        List<ReviewSourceView> result = new ArrayList<>(chain);
        result.add(new ReviewSourceView(
            "DECLARED_SOURCE",
            item.id(),
            item.sourceName(),
            completeMetadata(item.sourceName(), item.versionLabel()) ? "DECLARED" : "INCOMPLETE"
        ));
        return result;
    }

    private ReviewSourceView source(ReviewSourceEntity entity) {
        return new ReviewSourceView(entity.type(), entity.id(), entity.title(), entity.status());
    }

    private ReviewSourceView source(ReviewEntity entity) {
        return new ReviewSourceView(entity.type().name(), entity.id(), entity.title(), entity.status());
    }

    private ReviewEntity require(ReviewType type, String id) {
        return repository.find(type, id).orElseThrow(() -> notFound(type, id));
    }

    private ApiException notFound(ReviewType type, String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "ADMIN_REVIEW_NOT_FOUND", type.name() + " review item was not found: " + id);
    }

    private List<ReviewType> parseTypes(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return List.of(ReviewType.values());
        }
        List<ReviewType> types = new ArrayList<>();
        for (String candidate : normalized.split(",")) {
            ReviewType type = parseType(candidate);
            if (!types.contains(type)) {
                types.add(type);
            }
        }
        if (types.isEmpty()) {
            throw invalidType(value);
        }
        return List.copyOf(types);
    }

    private ReviewType parseType(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw invalidType(value);
        }
        try {
            return ReviewType.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw invalidType(value);
        }
    }

    private ApiException invalidType(String value) {
        return new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_REVIEW_TYPE_INVALID", "Unknown review type: " + value);
    }

    private String parseOptionalStatus(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : parseRequiredStatus(normalized);
    }

    private String parseRequiredStatus(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null || !REVIEW_STATUSES.contains(normalized.toUpperCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_REVIEW_STATUS_INVALID", "Unknown review status");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeId(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null || normalized.length() > 160) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_REVIEW_ID_INVALID", "Review item identifier is invalid");
        }
        return normalized;
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_PAGE_INVALID", "Pagination parameters are invalid");
        }
    }

    private static boolean completeMetadata(String sourceName, String versionLabel) {
        return nonBlank(sourceName) && nonBlank(versionLabel);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeOptional(String value) {
        return nonBlank(value) ? value.trim() : null;
    }

    private static String safeRequestId(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? "" : normalized.substring(0, Math.min(normalized.length(), 128));
    }

    private static String summary(ReviewEntity value) {
        return "status=" + value.status() + ";type=" + value.type().name();
    }
}

enum ReviewType {
    RESOURCE,
    KNOWLEDGE_CHUNK,
    PRESENTATION_MANIFEST,
    PRESENTATION_PAGE,
    DSVP_REQUEST_SNAPSHOT
}

record ReviewPage<T>(List<T> items, int page, int size, long total) {
}

record ReviewItemView(
    String type,
    String id,
    String title,
    String status,
    String chapterId,
    String versionLabel,
    boolean sourceComplete,
    Instant updatedAt
) {
}

record ReviewDetailView(ReviewItemView item, List<ReviewSourceView> sourceChain) {
}

record ReviewSourceView(String type, String id, String title, String status) {
}

record ReviewHistoryView(
    long id,
    String previousStatus,
    String nextStatus,
    String note,
    Long reviewerUserId,
    String requestId,
    Instant createdAt
) {
}

record SourceAssessment(List<ReviewSourceView> chain, boolean complete) {
}
