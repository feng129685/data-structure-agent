import type { JsonRecord } from "./api";

export type AnimationType = "stack" | "list" | "tree" | "queue" | "heap" | "hash" | "array";

export interface AnimationStep {
  op: string;
  label: string;
  note: string;
  value?: string | number | boolean | JsonRecord | null;
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

export interface GenerateAnimationRequest {
  prompt: string;
  preferredType?: AnimationType;
  chapterId?: string;
}

export type DsvpStructure =
  | "stack"
  | "queue"
  | "sequential_list"
  | "linked_list"
  | "tree"
  | "graph"
  | "heap"
  | "hash"
  | "array";

export type DsvpSourceType = "API" | "CLASSROOM" | "PPT";

export interface DsvpEvidenceContext {
  chapter_id?: string;
  lesson_id?: string;
  presentation_id?: string;
  presentation_page_id?: string;
  classroom_session_id?: string;
  source_type?: DsvpSourceType;
  source_ref?: string;
}

export interface DsvpInitialState {
  data: unknown[];
  metadata?: JsonRecord;
}

export interface DsvpRequest {
  version: "1.0";
  structure: DsvpStructure;
  operation: string;
  params: JsonRecord;
  initial_state: DsvpInitialState;
  options?: JsonRecord;
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

export type DsvpMatchSource =
  | "NONE"
  | "CLASSROOM_SESSION"
  | "PRESENTATION_PAGE"
  | "EXPLICIT_CHAPTER"
  | "ANIMATION_DEFINITION";

export interface DsvpSimulationResponse {
  protocol: "dsvp/1.0";
  request: DsvpRequest;
  trace: JsonRecord;
  animationData: AnimationDefinition;
  recordId?: string | null;
  evidencePersisted: boolean;
  animationRecordId?: string | null;
  resolvedChapterId?: string | null;
  matchSource: DsvpMatchSource;
}

export interface AnimationObservationRequest {
  observation: string;
}

export interface AnimationObservation {
  recordId: string;
  observation: string;
}
