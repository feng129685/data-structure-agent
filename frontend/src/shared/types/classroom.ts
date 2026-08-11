import type { JsonRecord } from "./api";

export interface ClassroomScript {
  id: string;
  chapterId: string;
  title: string;
  versionLabel: string;
}

export type ClassroomState =
  | "OPENING"
  | "EXPLAIN"
  | "QUESTION"
  | "WAITING"
  | "DISCUSS"
  | "BLACKBOARD"
  | "SUMMARY";

export type ClassroomAction = "ANSWER" | "PAUSE" | "RESUME" | "CONTINUE" | "FINISH";

export type ClassroomEvaluationStatus = "CORRECT" | "MISCONCEPTION" | "INCORRECT";

export interface ClassroomAnswerEvaluation {
  status: ClassroomEvaluationStatus;
  misconception?: string | null;
  feedback: string;
}

export interface ClassroomSession {
  id: string;
  userId: number;
  scriptId: string;
  state: ClassroomState;
  paused: boolean;
  summary?: string | null;
  stage: JsonRecord;
  answerEvaluation?: ClassroomAnswerEvaluation | null;
}

export interface CreateClassroomSessionRequest {
  scriptId: string;
}

export interface ClassroomActionRequest {
  action: ClassroomAction;
  content?: string;
}
