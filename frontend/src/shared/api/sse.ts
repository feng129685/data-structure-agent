import { parseSseStream, parseSseText as parseWireSseText, type SseEvent as WireSseEvent } from "./client";
import type { ChatResponse, ChatSource, SseEvent as ChatSseEvent } from "../types";

const CHAT_EVENT_NAMES = new Set(["sources", "delta", "done", "error"]);

export class ChatSseProtocolError extends Error {
  readonly code = "INVALID_SSE_EVENT";
  readonly event: string;

  constructor(event: string) {
    super(`Invalid payload for SSE event: ${event}`);
    this.name = "ChatSseProtocolError";
    this.event = event;
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseKnownEventData(event: WireSseEvent<unknown>): unknown {
  if (event.parsed !== undefined) return event.parsed;
  try {
    return JSON.parse(event.data);
  } catch {
    throw new ChatSseProtocolError(event.event);
  }
}

function isChatSource(value: unknown): value is ChatSource {
  if (!isRecord(value)) return false;
  return typeof value.id === "string"
    && typeof value.chapterId === "string"
    && typeof value.title === "string"
    && typeof value.content === "string"
    && typeof value.source === "string"
    && (value.pageLabel === null || typeof value.pageLabel === "string" || value.pageLabel === undefined)
    && typeof value.score === "number"
    && Number.isFinite(value.score)
    && typeof value.evidenceHash === "string"
    && value.evidenceHash.length > 0;
}

function isChatResponse(value: unknown): value is ChatResponse {
  if (!isRecord(value)) return false;
  return typeof value.answer === "string"
    && (value.sessionId === null || typeof value.sessionId === "string" || value.sessionId === undefined)
    && Array.isArray(value.sources)
    && value.sources.every(isChatSource)
    && typeof value.persisted === "boolean";
}

function normalizeEvent(event: WireSseEvent<unknown>): ChatSseEvent | null {
  if (!CHAT_EVENT_NAMES.has(event.event)) return null;
  const data = parseKnownEventData(event);
  if (event.event === "sources") {
    const sources = Array.isArray(data)
      ? data
      : isRecord(data) && Array.isArray(data.sources)
        ? data.sources
        : null;
    if (!sources || !sources.every(isChatSource)) throw new ChatSseProtocolError(event.event);
    return { event: "sources", data: Array.isArray(data) ? sources as ChatSource[] : { sources: sources as ChatSource[] } };
  }
  if (event.event === "delta") {
    if (!isRecord(data)
      || (typeof data.content !== "string" && typeof data.delta !== "string")) {
      throw new ChatSseProtocolError(event.event);
    }
    return { event: "delta", data: data as { content?: string; delta?: string } };
  }
  if (event.event === "done") {
    if (!isChatResponse(data)) throw new ChatSseProtocolError(event.event);
    return { event: "done", data };
  }
  if (!isRecord(data)) throw new ChatSseProtocolError(event.event);
  if (event.event === "error") return { event: "error", data: data as { code?: string; message?: string; requestId?: string; details?: string[] } };
  return null;
}

/**
 * Chat-specific normalization over the shared wire-level SSE parser.
 * The parser itself lives in `client.ts`; this module must not fork it.
 */
export function parseSseText(text: string): ChatSseEvent[] {
  return parseWireSseText(text)
    .map(normalizeEvent)
    .filter((event): event is ChatSseEvent => event !== null);
}

export async function* parseSseResponse(
  response: Response,
  signal?: AbortSignal,
): AsyncGenerator<ChatSseEvent> {
  if (!response.body) {
    yield* parseSseText(await response.text());
    return;
  }

  const stream = response.body;
  try {
    for await (const event of parseSseStream(stream, { signal })) {
      const normalized = normalizeEvent(event);
      if (normalized) yield normalized;
    }
  } finally {
    // The canonical parser releases its reader before this executes. Canceling
    // here stops upstream work when a component abandons a partial response.
    await stream.cancel().catch(() => undefined);
  }
}
