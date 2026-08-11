import type { IsoDateTime } from "./api";

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
}

export interface ChatResponse {
  answer: string;
  sessionId?: string | null;
  sources: ChatSource[];
  persisted: boolean;
}

export interface ChatSessionSummary {
  id: string;
  chapterId?: string | null;
  title: string;
  updatedAt: IsoDateTime;
  messageCount: number;
}

export interface ChatMessage {
  id: number;
  role: ChatRole;
  content: string;
  sources: ChatSource[];
  createdAt: IsoDateTime;
}

export interface ChatSession {
  id: string;
  chapterId?: string | null;
  title: string;
  updatedAt: IsoDateTime;
  messages: ChatMessage[];
}

export interface ChatStreamSourcesPayload {
  sources: ChatSource[];
}

export interface ChatStreamDeltaPayload {
  content: string;
}

export interface ChatStreamErrorPayload {
  code: string;
  message: string;
}

export type ChatStreamPayload =
  | ChatSource[]
  | ChatStreamDeltaPayload
  | ChatResponse
  | ChatStreamErrorPayload;
