import type { JsonRecord } from "./api";

export type AiOperation = "CHAT" | "CODE_ANALYSIS" | "ANIMATION_GENERATION";
export type AiQuotaStatus = "NOT_CONFIGURED" | "AVAILABLE" | "EXHAUSTED" | "CONCURRENCY_LIMITED";
export type AiEvidenceReason = "QUESTION_EVIDENCE_UNAVAILABLE" | "CONTEXT_EVIDENCE_UNAVAILABLE";

export type AiModelReason =
  | "PERSISTED_CONFIGURATION_READY"
  | "PERSISTED_CONFIGURATION_DISABLED"
  | "PERSISTED_QUOTA_NOT_CONFIGURED"
  | "PERSISTED_CONFIGURATION_UNAVAILABLE"
  | "ENVIRONMENT_CONFIGURATION_READY"
  | "ENVIRONMENT_QUOTA_NOT_CONFIGURED"
  | "ENVIRONMENT_CONFIGURATION_INCOMPLETE";

export interface AiCurrentContext {
  chapterId?: string | null;
  queryScoped: boolean;
}

export interface AiReadiness {
  operation: AiOperation;
  evidenceRequired: boolean;
  modelAvailable: boolean;
  modelReason: AiModelReason;
  evidenceAvailable: boolean;
  evidenceReason?: AiEvidenceReason | null;
  currentContext: AiCurrentContext;
  availableResourceCount: number;
  availableKnowledgeChunkCount: number;
  availableSourceCount: number;
  excludedOrUnverifiedCount: number;
  remainingDailyTokenQuota?: number | null;
  quotaStatus: AiQuotaStatus;
  allowFormalGeneration: boolean;
  blockingReasons: string[];
}

export type AiReadinessQuery = {
  operation?: AiOperation;
  chapterId?: string;
  prompt?: string;
};

export type AiCapabilityDetails = JsonRecord;
