import type { IsoDateTime, JsonRecord } from "./api";

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
  lastActivityAt?: IsoDateTime | null;
}

export interface LearningProgress {
  totalActivities: number;
  chapters: ChapterProgress[];
}

export type LearningEventType =
  | "RESOURCE_VIEW"
  | "RESOURCE_DOWNLOAD"
  | "REVIEW_COMPLETED"
  | "WEAKNESS_RECORDED";

export interface LearningEventRequest {
  eventType: LearningEventType;
  chapterId?: string | null;
  referenceId?: string | null;
  payload?: JsonRecord | null;
}

export interface LearningEvent {
  id: number;
  eventType: string;
  chapterId?: string | null;
  referenceId?: string | null;
  createdAt: IsoDateTime;
}
