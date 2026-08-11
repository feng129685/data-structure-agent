import type { JsonRecord } from "./api";

export type SupportedLanguage = "c" | "python";
export type CodeRunStatus = "success" | "compile_error" | "runtime_error";
export type CodeAnalysisStatus = CodeRunStatus | "unknown";

export interface CodeRunRequest {
  language: SupportedLanguage;
  code: string;
  stdin?: string;
  chapterId?: string;
}

export interface CodeRunResponse {
  language: SupportedLanguage;
  status: CodeRunStatus;
  stdout: string;
  stderr: string;
  durationMs: number;
  runId?: string | null;
}

export interface PersistedCodeAnalysisRequest {
  runId: string;
}

export interface AdHocCodeAnalysisRequest {
  runId?: null;
  language: SupportedLanguage;
  code: string;
  stdin?: string;
  stdout?: string;
  stderr?: string;
  status?: CodeAnalysisStatus;
  chapterId?: string;
}

export type CodeAnalysisRequest = PersistedCodeAnalysisRequest | AdHocCodeAnalysisRequest;

export interface CodeAnalysisResponse {
  analysis: string;
}

export type CodeExecutionMetadata = JsonRecord;
