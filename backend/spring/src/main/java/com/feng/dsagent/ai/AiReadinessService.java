package com.feng.dsagent.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.feng.dsagent.aiquota.AiQuotaExecutionService;
import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.knowledge.KnowledgeAudience;
import com.feng.dsagent.knowledge.KnowledgeEvidenceReadinessService;
import com.feng.dsagent.modelconfig.ModelGenerationReadiness;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiReadinessService {

    private final ModelGenerationReadiness modelReadiness;
    private final KnowledgeEvidenceReadinessService evidenceReadiness;
    private final AiQuotaExecutionService quotaExecution;

    public AiReadinessService(
        ModelGenerationReadiness modelReadiness,
        KnowledgeEvidenceReadinessService evidenceReadiness,
        AiQuotaExecutionService quotaExecution
    ) {
        this.modelReadiness = modelReadiness;
        this.evidenceReadiness = evidenceReadiness;
        this.quotaExecution = quotaExecution;
    }

    public Readiness current(KnowledgeAudience audience, String chapterId, String prompt) {
        return current(audience, null, chapterId, prompt);
    }

    public Readiness current(KnowledgeAudience audience, Long userId, String chapterId, String prompt) {
        return current(audience, userId, null, chapterId, prompt);
    }

    public Readiness current(
        KnowledgeAudience audience,
        Long userId,
        String requestedOperation,
        String chapterId,
        String prompt
    ) {
        Operation operation = Operation.parse(requestedOperation);
        String normalizedChapterId = normalize(chapterId);
        String normalizedPrompt = normalize(prompt);
        ModelGenerationReadiness.State model = modelReadiness.current();
        AiQuotaExecutionService.Availability quota = quotaExecution.availability(userId);
        KnowledgeEvidenceReadinessService.Snapshot evidence = evidenceReadiness.snapshot(
            audience,
            normalizedChapterId,
            normalizedPrompt
        );
        String evidenceReason = evidence.evidenceAvailable()
            ? null
            : evidence.queryScoped() ? "QUESTION_EVIDENCE_UNAVAILABLE" : "CONTEXT_EVIDENCE_UNAVAILABLE";
        List<String> blockingReasons = new ArrayList<>();
        if (!model.eligible()) {
            blockingReasons.add(model.reason().name());
        }
        if (operation.requiresEvidence() && evidenceReason != null) {
            blockingReasons.add(evidenceReason);
        }
        if (!quota.allowsFormalGeneration()) {
            blockingReasons.add("AI_QUOTA_" + quota.status());
        }
        return new Readiness(
            operation.name(),
            operation.requiresEvidence(),
            model.eligible(),
            model.reason().name(),
            evidence.evidenceAvailable(),
            evidenceReason,
            new CurrentContext(normalizedChapterId, evidence.queryScoped()),
            evidence.availableResourceCount(),
            evidence.availableKnowledgeChunkCount(),
            evidence.availableSourceCount(),
            evidence.excludedOrUnverifiedCount(),
            quota.remainingTokens(),
            quota.status(),
            model.eligible()
                && (!operation.requiresEvidence() || evidence.evidenceAvailable())
                && quota.allowsFormalGeneration(),
            List.copyOf(blockingReasons)
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record Readiness(
        String operation,
        boolean evidenceRequired,
        boolean modelAvailable,
        String modelReason,
        boolean evidenceAvailable,
        @JsonInclude(JsonInclude.Include.NON_NULL) String evidenceReason,
        CurrentContext currentContext,
        int availableResourceCount,
        int availableKnowledgeChunkCount,
        int availableSourceCount,
        int excludedOrUnverifiedCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long remainingDailyTokenQuota,
        String quotaStatus,
        boolean allowFormalGeneration,
        List<String> blockingReasons
    ) {
    }

    public record CurrentContext(String chapterId, boolean queryScoped) {
    }

    enum Operation {
        CHAT(true),
        CODE_ANALYSIS(false),
        ANIMATION_GENERATION(false);

        private final boolean requiresEvidence;

        Operation(boolean requiresEvidence) {
            this.requiresEvidence = requiresEvidence;
        }

        boolean requiresEvidence() {
            return requiresEvidence;
        }

        static Operation parse(String value) {
            if (value == null || value.isBlank()) {
                return CHAT;
            }
            String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                return Operation.valueOf(normalized);
            } catch (IllegalArgumentException error) {
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_READINESS_OPERATION_INVALID",
                    "Unsupported AI readiness operation"
                );
            }
        }
    }
}
