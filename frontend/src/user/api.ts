import type { ApiRequest, SseApiResponse } from "../shared/api";
import type {
  AiReadiness,
  AnimationObservation,
  AnimationObservationRequest,
  AnimationResponse,
  ChatRequest,
  ChatResponse,
  ChatSession,
  ChatSessionSummary,
  ClassroomActionRequest,
  ClassroomScript,
  ClassroomSession,
  CodeAnalysisRequest,
  CodeAnalysisResponse,
  CodeRunRequest,
  CodeRunResponse,
  DsvpRequest,
  DsvpSimulationResponse,
  LearningEvent,
  LearningEventRequest,
  LearningProgress,
  Resource,
} from "../shared/types/contracts";
import type { Chapter, KnowledgeSearchResponse } from "../shared/types/course";

interface JsonResponse<T> { kind: "json"; data: T }
interface EmptyResponse { kind: "empty"; data: undefined }
type UserRequest = ApiRequest;

function jsonData<T>(response: JsonResponse<T> | EmptyResponse): T {
  if (response.kind !== "json") throw new Error("接口未返回 JSON 数据");
  return response.data;
}

function encoded(value: string): string {
  return encodeURIComponent(value);
}

export interface KnowledgeSearchInput {
  query: string;
  chapterId?: string;
  limit?: number | string;
}

export interface UserApi {
  listChapters(): Promise<Chapter[]>;
  listResources(chapterId: string): Promise<Resource[]>;
  getResource(resourceId: string): Promise<Resource>;
  getResourceContent(resourceId: string): Promise<{ bytes: ArrayBuffer; disposition: string | null; contentType: string | null }>;
  searchKnowledge(input: KnowledgeSearchInput): Promise<KnowledgeSearchResponse>;
  getReadiness(input?: { operation?: "CHAT" | "CODE_ANALYSIS" | "ANIMATION_GENERATION"; chapterId?: string; prompt?: string }): Promise<AiReadiness>;
  chat(input: ChatRequest): Promise<ChatResponse>;
  streamChat(input: ChatRequest, signal?: AbortSignal): Promise<SseApiResponse>;
  listChatSessions(): Promise<ChatSessionSummary[]>;
  getChatSession(sessionId: string): Promise<ChatSession>;
  deleteChatSession(sessionId: string): Promise<void>;
  listClassroomScripts(chapterId?: string): Promise<ClassroomScript[]>;
  startClassroom(scriptId: string): Promise<ClassroomSession>;
  getClassroomSession(sessionId: string): Promise<ClassroomSession>;
  actInClassroom(sessionId: string, action: ClassroomActionRequest): Promise<ClassroomSession>;
  generateAnimation(input: { prompt: string; preferredType?: string; chapterId?: string }): Promise<AnimationResponse>;
  simulateAnimation(input: DsvpRequest): Promise<DsvpSimulationResponse>;
  saveObservation(animationId: string, input: AnimationObservationRequest): Promise<AnimationObservation>;
  runCode(input: CodeRunRequest): Promise<CodeRunResponse>;
  analyzeCode(input: CodeAnalysisRequest): Promise<CodeAnalysisResponse>;
  getLearningProgress(): Promise<LearningProgress>;
  recordLearningEvent(input: LearningEventRequest): Promise<LearningEvent>;
}

export function createUserApi(client: { request: UserRequest }): UserApi {
  const request = client.request;
  return {
    async listChapters() { return jsonData(await request<Chapter[]>("/chapters")); },
    async listResources(chapterId) { return jsonData(await request<Resource[]>(`/chapters/${encoded(chapterId)}/resources`)); },
    async getResource(resourceId) { return jsonData(await request<Resource>(`/resources/${encoded(resourceId)}`)); },
    async getResourceContent(resourceId) {
      const response = await request(`/resources/${encoded(resourceId)}/content`, { responseType: "binary" });
      return { bytes: response.data, disposition: response.headers.get("Content-Disposition"), contentType: response.headers.get("Content-Type") };
    },
    async searchKnowledge(input) {
      return jsonData(await request<KnowledgeSearchResponse>("/knowledge/search", { query: { q: input.query, chapterId: input.chapterId, limit: input.limit } }));
    },
    async getReadiness(input = {}) {
      return jsonData(await request<AiReadiness>("/ai/readiness", { query: input }));
    },
    async chat(input) { return jsonData(await request<ChatResponse>("/chat", { method: "POST", body: input })); },
    async streamChat(input, signal) {
      const response = await request("/chat/stream", { method: "POST", body: input, signal, responseType: "sse" });
      if (response.kind !== "sse") throw new Error("接口未返回 SSE 数据流");
      return response;
    },
    async listChatSessions() { return jsonData(await request<ChatSessionSummary[]>("/chat/sessions")); },
    async getChatSession(sessionId) { return jsonData(await request<ChatSession>(`/chat/sessions/${encoded(sessionId)}`)); },
    async deleteChatSession(sessionId) { await request(`/chat/sessions/${encoded(sessionId)}`, { method: "DELETE" }); },
    async listClassroomScripts(chapterId) { return jsonData(await request<ClassroomScript[]>("/classroom/scripts", { query: { chapterId } })); },
    async startClassroom(scriptId) { return jsonData(await request<ClassroomSession>("/classroom/sessions", { method: "POST", body: { scriptId } })); },
    async getClassroomSession(sessionId) { return jsonData(await request<ClassroomSession>(`/classroom/sessions/${encoded(sessionId)}`)); },
    async actInClassroom(sessionId, action) { return jsonData(await request<ClassroomSession>(`/classroom/sessions/${encoded(sessionId)}/actions`, { method: "POST", body: action })); },
    async generateAnimation(input) { return jsonData(await request<AnimationResponse>("/animations/generate", { method: "POST", body: input })); },
    async simulateAnimation(input) { return jsonData(await request<DsvpSimulationResponse>("/animations/simulate", { method: "POST", body: input })); },
    async saveObservation(animationId, input) { return jsonData(await request<AnimationObservation>(`/animations/${encoded(animationId)}/observations`, { method: "POST", body: input })); },
    async runCode(input) { return jsonData(await request<CodeRunResponse>("/code/runs", { method: "POST", body: input })); },
    async analyzeCode(input) { return jsonData(await request<CodeAnalysisResponse>("/code/analyze", { method: "POST", body: input })); },
    async getLearningProgress() { return jsonData(await request<LearningProgress>("/learning/progress")); },
    async recordLearningEvent(input) { return jsonData(await request<LearningEvent>("/learning/events", { method: "POST", body: input })); },
  };
}
