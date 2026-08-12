import type { ApiError } from "../types/api";
import { resolveApiBaseUrl } from "./base-url";

export type { ApiError } from "../types/api";

export type ResponseType = "auto" | "json" | "text" | "binary" | "sse";

export type QueryValue = string | number | boolean | null | undefined;

export type QueryParams = URLSearchParams | Record<string, QueryValue | QueryValue[]>;

export interface ApiRequestInit extends Omit<RequestInit, "body" | "headers" | "method"> {
  method?: string;
  headers?: HeadersInit;
  /** JSON-compatible values are serialized; other BodyInit values pass through. */
  body?: unknown;
  query?: QueryParams;
  requestId?: string;
  responseType?: ResponseType;
}

export interface ResponseOptions {
  responseType?: ResponseType;
  parseSseData?: boolean;
}

export interface ApiClientOptions {
  baseUrl?: string;
  fetcher?: typeof fetch;
  credentials?: RequestCredentials;
  defaultHeaders?: HeadersInit;
  requestIdFactory?: () => string;
  /** Optional in-memory Bearer token provider; never persisted by the client. */
  tokenProvider?: () => string | null | undefined;
}

export interface ApiResponseMeta {
  status: number;
  headers: Headers;
}

export interface JsonApiResponse<T> extends ApiResponseMeta {
  kind: "json";
  data: T;
  /** Alias for callers that use response-body terminology. */
  body: T;
}

export interface TextApiResponse extends ApiResponseMeta {
  kind: "text";
  data: string;
  body: string;
}

export interface BinaryApiResponse extends ApiResponseMeta {
  kind: "binary";
  data: ArrayBuffer;
  body: ArrayBuffer;
}

export interface EmptyApiResponse extends ApiResponseMeta {
  kind: "empty";
  data: undefined;
  body: undefined;
}

export interface SseEvent<T = unknown> {
  event: string;
  data: string;
  parsed?: T;
  id?: string;
  retry?: number;
}

export interface SseApiResponse<T = unknown> extends ApiResponseMeta {
  kind: "sse";
  events: AsyncGenerator<SseEvent<T>>;
  stream: ReadableStream<Uint8Array> | null;
}

export type ApiResponse<T = unknown> =
  | JsonApiResponse<T>
  | TextApiResponse
  | BinaryApiResponse
  | EmptyApiResponse
  | SseApiResponse<T>;

/**
 * Overloads keep the ordinary JSON path narrow while still allowing callers
 * to explicitly request binary, text, or SSE parsing.
 */
export interface ApiRequest {
  <T = unknown>(
    path: string,
    init: ApiRequestInit & { responseType: "binary" },
    responseOptions?: ResponseOptions,
  ): Promise<BinaryApiResponse>;
  <T = unknown>(
    path: string,
    init: ApiRequestInit & { responseType: "text" },
    responseOptions?: ResponseOptions,
  ): Promise<TextApiResponse>;
  <T = unknown>(
    path: string,
    init: ApiRequestInit & { responseType: "sse" },
    responseOptions?: ResponseOptions,
  ): Promise<SseApiResponse<T>>;
  <T = unknown>(
    path: string,
    init?: ApiRequestInit & { responseType?: "auto" | "json" },
    responseOptions?: ResponseOptions & { responseType?: "auto" | "json" },
  ): Promise<JsonApiResponse<T> | EmptyApiResponse>;
  <T = unknown>(
    path: string,
    init: ApiRequestInit,
    responseOptions: ResponseOptions & { responseType: "binary" },
  ): Promise<BinaryApiResponse>;
  <T = unknown>(
    path: string,
    init: ApiRequestInit,
    responseOptions: ResponseOptions & { responseType: "text" },
  ): Promise<TextApiResponse>;
  <T = unknown>(
    path: string,
    init: ApiRequestInit,
    responseOptions: ResponseOptions & { responseType: "sse" },
  ): Promise<SseApiResponse<T>>;
  <T = unknown>(path: string, init?: ApiRequestInit, responseOptions?: ResponseOptions): Promise<ApiResponse<T>>;
}

export interface ApiClientErrorInit {
  status: number;
  code: string;
  message: string;
  requestId: string;
  details: string[];
  headers?: Headers;
  body?: unknown;
  cause?: unknown;
}

export class ApiClientError extends Error implements ApiError {
  readonly status: number;
  readonly code: string;
  readonly requestId: string;
  readonly details: string[];
  readonly headers?: Headers;
  readonly body?: unknown;

  constructor(init: ApiClientErrorInit) {
    super(init.message);
    this.name = "ApiClientError";
    this.status = init.status;
    this.code = init.code;
    this.requestId = init.requestId;
    this.details = [...init.details];
    this.headers = init.headers;
    this.body = init.body;
    if (init.cause !== undefined) {
      Object.defineProperty(this, "cause", {
        configurable: true,
        enumerable: false,
        value: init.cause,
        writable: false,
      });
    }
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

export interface ParseSseOptions<T> {
  parseData?: (data: string) => T;
  signal?: AbortSignal;
}

/**
 * Parse a Spring `text/event-stream` body. The stream is deliberately exposed
 * as an async generator because the response body must be consumed once.
 */
export async function* parseSseStream<T = unknown>(
  stream: ReadableStream<Uint8Array> | null,
  options: ParseSseOptions<T> = {},
): AsyncGenerator<SseEvent<T>> {
  if (!stream) return;

  const reader = stream.getReader();
  let abortHandler: (() => void) | undefined;
  const decoder = new TextDecoder();
  let buffer = "";
  let eventName = "";
  let eventId: string | undefined;
  let retry: number | undefined;
  let dataLines: string[] = [];

  const reset = () => {
    eventName = "";
    eventId = undefined;
    retry = undefined;
    dataLines = [];
  };

  const dispatch = (): SseEvent<T> | undefined => {
    if (dataLines.length === 0) {
      reset();
      return undefined;
    }
    const data = dataLines.join("\n");
    let parsed: T | undefined;
    if (options.parseData) {
      parsed = options.parseData(data);
    } else {
      try {
        parsed = JSON.parse(data) as T;
      } catch {
        parsed = undefined;
      }
    }
    const event: SseEvent<T> = {
      event: eventName || "message",
      data,
      ...(parsed === undefined ? {} : { parsed }),
      ...(eventId === undefined ? {} : { id: eventId }),
      ...(retry === undefined ? {} : { retry }),
    };
    reset();
    return event;
  };

  const processLine = (line: string): SseEvent<T> | undefined => {
    if (line === "") return dispatch();
    if (line.startsWith(":")) return undefined;

    const separator = line.indexOf(":");
    const field = separator < 0 ? line : line.slice(0, separator);
    const rawValue = separator < 0 ? "" : line.slice(separator + 1);
    const value = rawValue.startsWith(" ") ? rawValue.slice(1) : rawValue;
    switch (field) {
      case "event":
        eventName = value;
        break;
      case "data":
        dataLines.push(value);
        break;
      case "id":
        if (!value.includes("\u0000")) eventId = value;
        break;
      case "retry": {
        const parsedRetry = Number.parseInt(value, 10);
        if (Number.isFinite(parsedRetry) && parsedRetry >= 0) retry = parsedRetry;
        break;
      }
      default:
        break;
    }
    return undefined;
  };

  const cancelReader = () => {
    void reader.cancel().catch(() => undefined);
  };

  if (options.signal) {
    if (options.signal.aborted) {
      cancelReader();
    } else {
      abortHandler = cancelReader;
      options.signal.addEventListener("abort", abortHandler, { once: true });
    }
  }

  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      while (true) {
        const match = /\r\n|\n|\r/.exec(buffer);
        if (!match || match.index === undefined) break;
        // A CR at the end of a chunk may be the first half of CRLF.
        if (match[0] === "\r" && match.index + 1 === buffer.length) break;
        const line = buffer.slice(0, match.index);
        buffer = buffer.slice(match.index + match[0].length);
        const event = processLine(line);
        if (event) yield event;
      }
    }
    buffer += decoder.decode();
    if (buffer.endsWith("\r")) {
      const event = processLine(buffer.slice(0, -1));
      if (event) yield event;
      const terminated = processLine("");
      if (terminated) yield terminated;
      buffer = "";
    } else if (buffer) {
      const event = processLine(buffer);
      if (event) yield event;
    }
    const finalEvent = dispatch();
    if (finalEvent) yield finalEvent;
  } finally {
    if (abortHandler && options.signal) {
      options.signal.removeEventListener("abort", abortHandler);
    }
    await reader.cancel().catch(() => undefined);
    reset();
    reader.releaseLock();
  }
}

/** Parse an in-memory SSE payload, useful for fixtures and unit tests. */
export function parseSseText<T = unknown>(text: string, options: ParseSseOptions<T> = {}): SseEvent<T>[] {
  const events: SseEvent<T>[] = [];
  let eventName = "";
  let eventId: string | undefined;
  let retry: number | undefined;
  let dataLines: string[] = [];
  const reset = () => {
    eventName = "";
    eventId = undefined;
    retry = undefined;
    dataLines = [];
  };
  const dispatch = () => {
    if (dataLines.length === 0) {
      reset();
      return;
    }
    const data = dataLines.join("\n");
    let parsed: T | undefined;
    if (options.parseData) {
      parsed = options.parseData(data);
    } else {
      try {
        parsed = JSON.parse(data) as T;
      } catch {
        parsed = undefined;
      }
    }
    events.push({
      event: eventName || "message",
      data,
      ...(parsed === undefined ? {} : { parsed }),
      ...(eventId === undefined ? {} : { id: eventId }),
      ...(retry === undefined ? {} : { retry }),
    });
    reset();
  };
  const lines = text.split(/\r\n|\n|\r/);
  for (const line of lines) {
    if (line === "") {
      dispatch();
      continue;
    }
    if (line.startsWith(":")) continue;
    const separator = line.indexOf(":");
    const field = separator < 0 ? line : line.slice(0, separator);
    const rawValue = separator < 0 ? "" : line.slice(separator + 1);
    const value = rawValue.startsWith(" ") ? rawValue.slice(1) : rawValue;
    switch (field) {
      case "event":
        eventName = value;
        break;
      case "data":
        dataLines.push(value);
        break;
      case "id":
        if (!value.includes("\u0000")) eventId = value;
        break;
      case "retry": {
        const parsedRetry = Number.parseInt(value, 10);
        if (Number.isFinite(parsedRetry) && parsedRetry >= 0) retry = parsedRetry;
        break;
      }
      default:
        break;
    }
  }
  dispatch();
  return events;
}

export const parseSseEvents = parseSseText;

export function createRequestId(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") return cryptoApi.randomUUID();
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

export function isApiClientError(value: unknown): value is ApiClientError {
  return value instanceof ApiClientError;
}

export function parseApiError(
  value: unknown,
  status: number,
  requestId: string,
  headers?: Headers,
): ApiClientError {
  const body = isRecord(value) ? value : undefined;
  const headerRequestId = headers?.get("X-Request-Id") ?? headers?.get("x-request-id");
  const code = typeof body?.code === "string" && body.code.trim() ? body.code : `HTTP_${status}`;
  const message = typeof body?.message === "string" && body.message.trim()
    ? body.message
    : `Request failed with HTTP ${status}`;
  const details = Array.isArray(body?.details)
    ? body.details.filter((detail): detail is string => typeof detail === "string")
    : [];
  return new ApiClientError({
    status,
    code,
    message,
    requestId: typeof body?.requestId === "string" && body.requestId.trim()
      ? body.requestId
      : headerRequestId || requestId,
    details,
    headers,
    body: value,
  });
}

export function createApiClient(options: ApiClientOptions = {}) {
  const baseUrl = options.baseUrl ?? resolveApiBaseUrl();
  const fetcher = options.fetcher ?? ((...args: Parameters<typeof fetch>) => globalThis.fetch(...args));
  const defaultCredentials = options.credentials ?? "include";
  const requestIdFactory = options.requestIdFactory ?? createRequestId;
  const tokenProvider = options.tokenProvider;

  const request = (async <T = unknown>(
    path: string,
    init: ApiRequestInit = {},
    responseOptions: ResponseOptions = {},
  ): Promise<ApiResponse<T>> => {
    const headers = new Headers(options.defaultHeaders);
    new Headers(init.headers).forEach((value, key) => headers.set(key, value));
    const requestId = init.requestId ?? headers.get("X-Request-Id") ?? requestIdFactory();
    headers.set("X-Request-Id", requestId);
    const accessToken = tokenProvider?.();
    if (accessToken && !headers.has("Authorization")) headers.set("Authorization", `Bearer ${accessToken}`);

    const requestedResponseType = responseOptions.responseType ?? init.responseType ?? "auto";
    if (requestedResponseType === "sse") {
      if (!headers.has("Accept")) headers.set("Accept", "text/event-stream");
    } else if (!headers.has("Accept")) {
      headers.set("Accept", "application/json");
    }

    const body = serializeBody(init.body, headers);
    const { query, requestId: _requestId, responseType: _responseType, body: _body, headers: _headers, ...rest } = init;
    const outgoingHeaders: Record<string, string> = {};
    headers.forEach((value, key) => {
      outgoingHeaders[key === "x-request-id" ? "X-Request-Id" : key] = value;
    });
    // Keep the documented spelling visible to fetch spies and browser tooling;
    // HTTP header names remain case-insensitive on the wire.
    const outgoingRequestId = headers.get("X-Request-Id");
    if (outgoingRequestId) outgoingHeaders["X-Request-Id"] = outgoingRequestId;

    const fetchInit: RequestInit = {
      ...rest,
      ...(init.method === undefined ? {} : { method: init.method }),
      headers: outgoingHeaders,
      credentials: init.credentials ?? defaultCredentials,
      ...(body === undefined ? {} : { body }),
    };

    const response = await fetcher(buildUrl(baseUrl, path, query), fetchInit);
    const responseHeaders = new Headers(response.headers);
    if (!response.ok) {
      const errorBody = await readErrorBody(response);
      throw parseApiError(errorBody, response.status, requestId, responseHeaders);
    }

    if (response.status === 204 || response.status === 205 || response.status === 304) {
      return { kind: "empty", status: response.status, headers: responseHeaders, data: undefined, body: undefined };
    }

    const effectiveType = inferResponseType(response, requestedResponseType);
    if (effectiveType === "sse") {
      const sseOptions: ParseSseOptions<T> = responseOptions.parseSseData === false
        ? { parseData: (data) => data as T }
        : responseOptions.parseSseData === true
          ? { parseData: (data) => JSON.parse(data) as T }
          : {};
      return {
        kind: "sse",
        status: response.status,
        headers: responseHeaders,
        stream: response.body,
        events: parseSseStream(response.body, { ...sseOptions, signal: init.signal ?? undefined }),
      } as SseApiResponse<T>;
    }
    if (effectiveType === "binary") {
      const data = await response.arrayBuffer();
      return { kind: "binary", status: response.status, headers: responseHeaders, data, body: data };
    }
    if (effectiveType === "text") {
      const data = await response.text();
      return { kind: "text", status: response.status, headers: responseHeaders, data, body: data };
    }

    const text = await response.text();
    if (!text.trim()) {
      return { kind: "empty", status: response.status, headers: responseHeaders, data: undefined, body: undefined };
    }
    try {
      const data = JSON.parse(text) as T;
      return { kind: "json", status: response.status, headers: responseHeaders, data, body: data };
    } catch (cause) {
      throw new ApiClientError({
        status: response.status,
        code: "INVALID_RESPONSE_BODY",
        message: "The server returned an invalid JSON response",
        requestId: responseHeaders.get("X-Request-Id") ?? requestId,
        details: [],
        headers: responseHeaders,
        body: text,
        cause,
      });
    }
  }) as ApiRequest;

  const json = async <T = unknown>(path: string, init: ApiRequestInit = {}): Promise<T | undefined> => {
    const response = await request<T>(path, { ...init, responseType: "json" });
    return response.kind === "json" ? response.data : undefined;
  };

  const stream = async <T = unknown>(path: string, init: ApiRequestInit = {}): Promise<AsyncGenerator<SseEvent<T>>> => {
    const response = await request<T>(path, { ...init, responseType: "sse" });
    if (response.kind !== "sse") {
      throw new ApiClientError({
        status: response.status,
        code: "INVALID_RESPONSE_TYPE",
        message: "The server did not return an SSE stream",
        requestId: response.headers.get("X-Request-Id") ?? "",
        details: [],
        headers: response.headers,
      });
    }
    return response.events;
  };

  return {
    request,
    json,
    stream,
    get: <T = unknown>(path: string, init: ApiRequestInit = {}, responseOptions?: ResponseOptions) =>
      request<T>(path, { ...init, method: "GET" }, responseOptions),
    post: <T = unknown>(path: string, body?: unknown, init: ApiRequestInit = {}, responseOptions?: ResponseOptions) =>
      request<T>(path, { ...init, method: "POST", body }, responseOptions),
    put: <T = unknown>(path: string, body?: unknown, init: ApiRequestInit = {}, responseOptions?: ResponseOptions) =>
      request<T>(path, { ...init, method: "PUT", body }, responseOptions),
    patch: <T = unknown>(path: string, body?: unknown, init: ApiRequestInit = {}, responseOptions?: ResponseOptions) =>
      request<T>(path, { ...init, method: "PATCH", body }, responseOptions),
    delete: <T = unknown>(path: string, init: ApiRequestInit = {}, responseOptions?: ResponseOptions) =>
      request<T>(path, { ...init, method: "DELETE" }, responseOptions),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;

function buildUrl(baseUrl: string, path: string, query?: QueryParams): string {
  const target = path.trim();
  let url: string;
  if (/^[a-z][a-z\d+.-]*:\/\//i.test(target)) {
    url = target;
  } else {
    const normalizedPath = target.startsWith("/") ? target : `/${target}`;
    const normalizedBase = baseUrl.replace(/\/+$/, "");
    const absoluteBase = /^[a-z][a-z\d+.-]*:\/\//i.test(normalizedBase)
      ? new URL(normalizedBase)
      : undefined;
    const basePath = absoluteBase?.pathname.replace(/\/+$/, "") ?? normalizedBase;
    const hasV1Path = normalizedPath === "/api/v1" || normalizedPath.startsWith("/api/v1/");
    if (hasV1Path && basePath.endsWith("/api/v1")) {
      const prefix = basePath.slice(0, -"/api/v1".length);
      url = absoluteBase
        ? `${absoluteBase.origin}${prefix}${normalizedPath}`
        : `${prefix}${normalizedPath}`;
    } else if (normalizedPath === basePath || normalizedPath.startsWith(`${basePath}/`)) {
      url = absoluteBase ? `${absoluteBase.origin}${normalizedPath}` : normalizedPath;
    } else {
      url = `${normalizedBase}${normalizedPath}` || normalizedPath;
    }
  }
  if (!query) return url;
  const params = query instanceof URLSearchParams ? query : new URLSearchParams();
  if (!(query instanceof URLSearchParams)) {
    for (const [key, value] of Object.entries(query)) {
      for (const item of Array.isArray(value) ? value : [value]) {
        if (item !== undefined && item !== null) params.append(key, String(item));
      }
    }
  }
  const queryString = params.toString();
  if (!queryString) return url;
  return `${url}${url.includes("?") ? "&" : "?"}${queryString}`;
}

function serializeBody(body: unknown, headers: Headers): BodyInit | undefined {
  if (body === undefined || body === null) return undefined;
  if (typeof body === "string") {
    if (!headers.has("Content-Type") && /^[\s]*[\[{]/.test(body)) {
      headers.set("Content-Type", "application/json");
    }
    return body;
  }
  if (isBodyInit(body)) return body;
  if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return JSON.stringify(body);
}

function isBodyInit(value: unknown): value is BodyInit {
  return typeof value === "string"
    || (typeof Blob !== "undefined" && value instanceof Blob)
    || (typeof FormData !== "undefined" && value instanceof FormData)
    || (typeof URLSearchParams !== "undefined" && value instanceof URLSearchParams)
    || value instanceof ArrayBuffer
    || (ArrayBuffer.isView(value) && typeof value !== "function")
    || (typeof ReadableStream !== "undefined" && value instanceof ReadableStream);
}

function inferResponseType(response: Response, requested: ResponseType): Exclude<ResponseType, "auto"> {
  if (requested !== "auto") return requested;
  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  if (contentType.includes("text/event-stream")) return "sse";
  if (contentType.includes("application/json") || contentType.includes("+json")) return "json";
  if (contentType.startsWith("text/")) return "text";
  if (
    contentType.includes("application/octet-stream")
    || contentType.includes("application/pdf")
    || contentType.startsWith("image/")
    || contentType.startsWith("audio/")
    || contentType.startsWith("video/")
  ) return "binary";
  return "json";
}

async function readErrorBody(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  const text = await response.text();
  if (!text.trim()) return undefined;
  if (contentType.includes("json") || contentType.includes("+json")) {
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
