import type { ChatSource, SseEvent } from "../types";

function parseJson<T>(value: string): T | string {
  try {
    return JSON.parse(value) as T;
  } catch {
    return value;
  }
}

function normalizeEvent(event: string, raw: unknown): SseEvent | null {
  const data = raw && typeof raw === "object" ? raw as Record<string, unknown> : raw;
  if (event === "sources") {
    const sources = Array.isArray(data) ? data : (data && typeof data === "object" && Array.isArray((data as Record<string, unknown>).sources) ? (data as Record<string, unknown>).sources : []);
    return { event: "sources", data: Array.isArray(data) ? sources as ChatSource[] : { sources: sources as ChatSource[] } };
  }
  if (event === "delta") {
    if (typeof data === "string") return { event: "delta", data: { content: data } };
    return { event: "delta", data: (data || {}) as { content?: string; delta?: string } };
  }
  if (event === "done") return { event: "done", data: (data || {}) as { sessionId?: string | null; persisted?: boolean; answer?: string } };
  if (event === "error") return { event: "error", data: (data || {}) as { code?: string; message?: string; requestId?: string; details?: string[] } };
  return null;
}

/** Parse Spring's named SSE events. Blank lines terminate an event. */
export function parseSseText(text: string): SseEvent[] {
  const events: SseEvent[] = [];
  let eventName = "message";
  let dataLines: string[] = [];
  const flush = () => {
    if (!dataLines.length) return;
    const parsed = normalizeEvent(eventName, parseJson(dataLines.join("\n")));
    if (parsed) events.push(parsed);
    eventName = "message";
    dataLines = [];
  };
  for (const line of text.replace(/\r\n/g, "\n").split("\n")) {
    if (!line.trim()) { flush(); continue; }
    if (line.startsWith(":")) continue;
    const separator = line.indexOf(":");
    const field = separator < 0 ? line : line.slice(0, separator);
    const value = separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "");
    if (field === "event") eventName = value;
    if (field === "data") dataLines.push(value);
  }
  flush();
  return events;
}

export async function* parseSseResponse(response: Response): AsyncGenerator<SseEvent> {
  if (!response.body) {
    const text = await response.text();
    yield* parseSseText(text);
    return;
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventName = "message";
  let dataLines: string[] = [];
  const flush = function* (): Generator<SseEvent> {
    if (!dataLines.length) return;
    const parsed = normalizeEvent(eventName, parseJson(dataLines.join("\n")));
    if (parsed) yield parsed;
    eventName = "message";
    dataLines = [];
  };
  while (true) {
    const chunk = await reader.read();
    buffer += decoder.decode(chunk.value || new Uint8Array(), { stream: !chunk.done });
    const lines = buffer.replace(/\r\n/g, "\n").split("\n");
    buffer = lines.pop() || "";
    for (const line of lines) {
      if (!line.trim()) { yield* flush(); continue; }
      if (line.startsWith(":")) continue;
      const separator = line.indexOf(":");
      const field = separator < 0 ? line : line.slice(0, separator);
      const value = separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "");
      if (field === "event") eventName = value;
      if (field === "data") dataLines.push(value);
    }
    if (chunk.done) break;
  }
  if (buffer) {
    const separator = buffer.indexOf(":");
    const field = separator < 0 ? buffer : buffer.slice(0, separator);
    const value = separator < 0 ? "" : buffer.slice(separator + 1).replace(/^ /, "");
    if (field === "event") eventName = value;
    if (field === "data") dataLines.push(value);
  }
  yield* flush();
}
