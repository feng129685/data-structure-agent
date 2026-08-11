export interface ApiErrorPayload {
  code?: string;
  message?: string;
  requestId?: string;
  details?: string[];
}

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string;
  readonly details: string[];
  readonly method?: string;
  readonly path?: string;

  constructor(options: {
    status: number;
    code: string;
    message: string;
    requestId?: string;
    details?: string[];
    method?: string;
    path?: string;
  }) {
    super(options.message);
    this.name = "ApiClientError";
    this.status = options.status;
    this.code = options.code;
    this.requestId = options.requestId || "";
    this.details = Array.isArray(options.details) ? options.details : [];
    this.method = options.method;
    this.path = options.path;
  }
}

export function isApiClientError(value: unknown): value is ApiClientError {
  return value instanceof ApiClientError || Boolean(value && typeof value === "object" && "status" in value && "code" in value);
}
