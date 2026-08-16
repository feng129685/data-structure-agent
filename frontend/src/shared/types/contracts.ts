/** TypeScript representations of the frozen Spring v1 API contract. */

export type Role = "STUDENT" | "TEACHER" | "ADMIN";
export type LicenseScope = "PUBLIC" | "TEAM_ONLY" | "CLASSROOM_ONLY";

export interface User {
  id: number;
  email: string;
  username?: string | null;
  roles: Role[];
}

export interface AuthResponse {
  token: string;
  user: User;
}

export type VerificationPurpose = "register" | "reset";
export interface RequestCodeRequest {
  email: string;
  purpose: VerificationPurpose;
}
export interface VerificationCodeDelivery {
  message: string;
}
export interface LoginRequest {
  email?: string;
  username?: string;
  password: string;
}
export interface RegisterRequest {
  email: string;
  code: string;
  password: string;
}
export interface ResetPasswordRequest extends RegisterRequest {}

export interface Chapter {
  id: string;
  chapterNumber: number;
  title: string;
  summary: string;
}

export type ReviewStatus = "PUBLISHED";
export interface Resource {
  id: string;
  chapterId: string;
  type: string;
  title: string;
  description: string;
  sourceName: string;
  versionLabel: string;
  reviewStatus: ReviewStatus;
  licenseScope: LicenseScope;
  contentUrl: string | null;
}

export type KnowledgeKind = "answer" | "textbook";
export interface KnowledgeSearchResult {
  id: string;
  chapterId: string | null;
  title: string;
  lessonNumber: string | null;
  kind: KnowledgeKind;
  source: string;
  pageLabel: string | null;
  sourceLabel: string;
  locationLabel: string;
  reviewStatus: string;
  publicationStatus: "PUBLISHED";
  excerpt: string;
  score: number;
}
export interface KnowledgeSearchResponse {
  ok: true;
  query: string;
  results: KnowledgeSearchResult[];
}

export type ChatRole = "user" | "assistant";
export interface ChatTurn {
  role: ChatRole;
  content: string;
}
export interface ChatRequest {
  prompt: string;
  chapterId?: string;
  sessionId?: string;
  history?: ChatTurn[];
}
export interface ChatSource {
  id: string;
  chapterId: string;
  title: string;
  content: string;
  source: string;
  pageLabel: string | null;
  score: number;
  evidenceHash: string;
}
export interface ChatResponse {
  answer: string;
  sessionId?: string | null;
  sources: ChatSource[];
  persisted: boolean;
}
export interface ChatSessionSummary {
  id: string;
  chapterId: string | null;
  title: string;
  updatedAt: string;
  messageCount: number;
}
export interface ChatMessage {
  id: number;
  role: ChatRole;
  content: string;
  sources: ChatSource[];
  createdAt: string;
}
export interface ChatSession {
  id: string;
  chapterId: string | null;
  title: string;
  updatedAt: string;
  messages: ChatMessage[];
}

export interface ClassroomScript {
  id: string;
  chapterId: string;
  title: string;
  versionLabel: string;
}
export type ClassroomState = "OPENING" | "EXPLAIN" | "QUESTION" | "WAITING" | "DISCUSS" | "BLACKBOARD" | "SUMMARY";
export type ClassroomAction = "ANSWER" | "PAUSE" | "RESUME" | "CONTINUE" | "FINISH";
export type ClassroomAnswerStatus = "CORRECT" | "MISCONCEPTION" | "INCORRECT";
export interface ClassroomAnswerEvaluation {
  status: ClassroomAnswerStatus;
  misconception: string | null;
  feedback: string;
}
export interface ClassroomSession {
  id: string;
  userId: number;
  scriptId: string;
  state: ClassroomState;
  paused: boolean;
  summary: string | null;
  stage: Record<string, unknown>;
  answerEvaluation?: ClassroomAnswerEvaluation | null;
}
export interface ClassroomActionRequest {
  action: ClassroomAction;
  content?: string;
}

export type AnimationType = "stack" | "list" | "tree" | "queue" | "heap" | "hash" | "array";
export interface AnimationStep {
  op: string;
  label: string;
  note: string;
  value?: string | number | boolean | Record<string, unknown> | null;
  index?: number | null;
  node?: number | null;
  i?: number | null;
  j?: number | null;
  key?: string | null;
  val?: string | null;
}
export interface AnimationDefinition {
  animation: true;
  type: AnimationType;
  title: string;
  description: string;
  initial: unknown[];
  steps: AnimationStep[];
}
export interface AnimationResponse {
  definition: AnimationDefinition;
  recordId?: string | null;
  persisted: boolean;
}
export type DsvpStructure = "stack" | "queue" | "sequential_list" | "linked_list" | "tree" | "graph" | "heap" | "hash" | "array";
export interface DsvpEvidenceContext {
  chapter_id?: string;
  lesson_id?: string;
  presentation_id?: string;
  presentation_page_id?: string;
  classroom_session_id?: string;
  source_type?: "API" | "CLASSROOM" | "PPT";
  source_ref?: string;
}
export interface DsvpRequest {
  version: "1.0";
  structure: DsvpStructure;
  operation: string;
  params: Record<string, unknown>;
  initial_state: { data: unknown[]; metadata?: Record<string, unknown> };
  options?: Record<string, unknown>;
  context?: DsvpEvidenceContext;
  chapter_id?: string;
  chapterId?: string;
  lesson_id?: string;
  lessonId?: string;
  presentation_id?: string;
  presentationId?: string;
  presentation_page_id?: string;
  presentationPageId?: string;
  classroom_session_id?: string;
  classroomSessionId?: string;
  source_ref?: string;
}
export type MatchSource = "NONE" | "CLASSROOM_SESSION" | "PRESENTATION_PAGE" | "EXPLICIT_CHAPTER" | "ANIMATION_DEFINITION";
export interface DsvpSimulationResponse {
  protocol: "dsvp/1.0";
  request: DsvpRequest;
  trace: Record<string, unknown>;
  animationData: AnimationDefinition;
  recordId?: string | null;
  evidencePersisted: boolean;
  animationRecordId?: string | null;
  resolvedChapterId?: string | null;
  matchSource: MatchSource;
}
export interface AnimationObservationRequest { observation: string }
export interface AnimationObservation { recordId: string; observation: string }

export type CodeLanguage = "c" | "python";
export type CodeRunStatus = "success" | "compile_error" | "runtime_error";
export interface CodeRunRequest { language: CodeLanguage; code: string; stdin?: string; chapterId?: string }
export interface CodeRunResponse { language: CodeLanguage; status: CodeRunStatus; stdout: string; stderr: string; durationMs: number; runId: string | null }
export type CodeAnalysisRequest =
  | { runId: string }
  | { runId?: null; language: CodeLanguage; code: string; stdin?: string; stdout?: string; stderr?: string; status?: CodeRunStatus | "unknown"; chapterId?: string };
export interface CodeAnalysisResponse { analysis: string }

export interface ChapterProgress {
  chapterId: string;
  chapterNumber: number;
  title: string;
  chatCount: number;
  classroomCount: number;
  animationCount: number;
  codeRunCount: number;
  eventCount: number;
  totalActivities: number;
  lastActivityAt: string | null;
}
export interface LearningProgress { totalActivities: number; chapters: ChapterProgress[] }
export type LearningEventType = "RESOURCE_VIEW" | "RESOURCE_DOWNLOAD" | "REVIEW_COMPLETED" | "WEAKNESS_RECORDED";
export interface LearningEventRequest { eventType: LearningEventType; chapterId?: string | null; referenceId?: string | null; payload?: Record<string, unknown> | null }
export interface LearningEvent { id: number; eventType: string; chapterId: string | null; referenceId: string | null; createdAt: string }

export type AiOperation = "CHAT" | "CODE_ANALYSIS" | "ANIMATION_GENERATION";
export type AiModelReason = "PERSISTED_CONFIGURATION_READY" | "PERSISTED_CONFIGURATION_DISABLED" | "PERSISTED_QUOTA_NOT_CONFIGURED" | "PERSISTED_CONFIGURATION_UNAVAILABLE" | "ENVIRONMENT_CONFIGURATION_READY" | "ENVIRONMENT_QUOTA_NOT_CONFIGURED" | "ENVIRONMENT_CONFIGURATION_INCOMPLETE";
export type AiQuotaStatus = "NOT_CONFIGURED" | "AVAILABLE" | "EXHAUSTED" | "CONCURRENCY_LIMITED";
export interface AiCurrentContext { chapterId: string | null; queryScoped: boolean }
export interface AiReadiness {
  operation: AiOperation;
  evidenceRequired: boolean;
  modelAvailable: boolean;
  modelReason: AiModelReason;
  evidenceAvailable: boolean;
  evidenceReason: "QUESTION_EVIDENCE_UNAVAILABLE" | "CONTEXT_EVIDENCE_UNAVAILABLE" | null;
  currentContext: AiCurrentContext;
  availableResourceCount: number;
  availableKnowledgeChunkCount: number;
  availableSourceCount: number;
  excludedOrUnverifiedCount: number;
  remainingDailyTokenQuota: number | null;
  quotaStatus: AiQuotaStatus;
  allowFormalGeneration: boolean;
  blockingReasons: string[];
}

export type AdminModuleStatus = "AVAILABLE" | "UNAVAILABLE" | "NOT_CONFIGURED";
export interface AdminModuleCapability { available: boolean; status: AdminModuleStatus; reason?: string | null }
export interface AdminServiceStatus { name: "spring"; version: string; status: "AVAILABLE" | "UNAVAILABLE" }
export interface AdminCapability { userId: number; roles: Role[]; modules: Record<string, AdminModuleCapability>; service: AdminServiceStatus }
export type AdminUserStatus = "ACTIVE" | "DISABLED";
export interface AdminUser { id: number; email: string; status: AdminUserStatus; disabledReason: string | null; disabledAt: string | null; roles: Role[]; createdAt: string; updatedAt: string }
export interface Page<T> { items: T[]; page: number; size: number; total: number }
export interface AdminUserStatusRequest { status: AdminUserStatus; reason?: string | null }
export interface AdminUserRolesRequest { roles: Role[] }
export interface AdminAuditEvent { id: number; actorUserId: number; action: string; targetType: string; targetId: string; result: string; requestId: string; beforeSummary: string; afterSummary: string; createdAt: string }
export interface BackgroundTask { id: number; taskType: string; status: "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELED"; createdAt: string; startedAt: string | null; deadlineAt: string | null; heartbeatAt: string | null; retryCount: number; maxAttempts: number; requestId: string }

export type SseEvent =
  | { event: "sources"; data: { sources: ChatSource[] } | ChatSource[] }
  | { event: "delta"; data: { content?: string; delta?: string } }
  | { event: "done"; data: ChatResponse }
  | { event: "error"; data: { code?: string; message?: string; requestId?: string; details?: string[] } };
