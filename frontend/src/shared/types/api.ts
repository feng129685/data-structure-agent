/** Shared wire-level primitives for the Spring v1 API. */
export type JsonRecord = Record<string, unknown>;

export type IsoDateTime = string;

export interface ApiError {
  code: string;
  message: string;
  requestId: string;
  details: string[];
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export type ApiErrorStatus =
  | 400
  | 401
  | 403
  | 404
  | 409
  | 413
  | 429
  | 500
  | 502
  | 503
  | 504
  | number;
