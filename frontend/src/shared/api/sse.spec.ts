import { describe, expect, it } from "vitest";
import { parseSseResponse, parseSseText } from "./sse";

const encoder = new TextEncoder();
const source = {
  id: "chunk-1",
  chapterId: "chapter-1",
  title: "栈的入栈操作",
  content: "入栈操作将元素放到栈顶。",
  source: "resource-1",
  pageLabel: "第 3 页",
  score: 0.92,
  evidenceHash: "sha256:content-version-1",
};

describe("chat SSE adapter", () => {
  it("keeps named chat events compatible with the shared wire parser", () => {
    const done = { answer: "hello", sessionId: "session-1", sources: [source], persisted: true };
    expect(parseSseText(`event: delta\ndata: {"content":"hello"}\n\nevent: done\ndata: ${JSON.stringify(done)}\n`)).toEqual([
      { event: "delta", data: { content: "hello" } },
      { event: "done", data: done },
    ]);
  });

  it.each([
    "event: sources\ndata: {\"unexpected\":true}\n\n",
    "event: delta\ndata: {not-json}\n\n",
  ])("rejects malformed payloads for known chat events", (payload) => {
    expect(() => parseSseText(payload)).toThrowError(expect.objectContaining({
      name: "ChatSseProtocolError",
      code: "INVALID_SSE_EVENT",
    }));
  });

  it("rejects sources that omit the frozen evidence hash", () => {
    const { evidenceHash: _evidenceHash, ...sourceWithoutHash } = source;
    expect(() => parseSseText(`event: sources\ndata: ${JSON.stringify([sourceWithoutHash])}\n\n`))
      .toThrowError(expect.objectContaining({ code: "INVALID_SSE_EVENT", event: "sources" }));
  });

  it("rejects a done event that is not a complete ChatResponse", () => {
    expect(() => parseSseText("event: done\ndata: {\"persisted\":true}\n\n"))
      .toThrowError(expect.objectContaining({ code: "INVALID_SSE_EVENT", event: "done" }));
  });

  it("ignores unknown named events without hiding malformed known events", () => {
    expect(parseSseText("event: heartbeat\ndata: {\"at\":1}\n\n")).toEqual([]);
  });

  it("cancels the response reader when an SSE consumer stops early", async () => {
    let canceled = false;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode("event: delta\ndata: {\"content\":\"partial\"}\n\n"));
      },
      cancel() {
        canceled = true;
      },
    });
    const events = parseSseResponse(new Response(stream));

    await expect(events.next()).resolves.toMatchObject({ value: { event: "delta", data: { content: "partial" } } });
    await events.return(undefined);

    expect(canceled).toBe(true);
  });

  it("passes an abort signal through to the shared parser", async () => {
    let canceled = false;
    const stream = new ReadableStream<Uint8Array>({
      cancel() {
        canceled = true;
      },
    });
    const controller = new AbortController();
    const events = parseSseResponse(new Response(stream), controller.signal);

    const next = events.next();
    controller.abort();

    await expect(next).resolves.toEqual({ done: true, value: undefined });
    expect(canceled).toBe(true);
  });

  it("propagates an abort signal through the chat adapter while a response is pending", async () => {
    let canceled = false;
    const stream = new ReadableStream<Uint8Array>({
      cancel() {
        canceled = true;
      },
    });
    const controller = new AbortController();
    const events = parseSseResponse(new Response(stream), controller.signal);
    const next = events.next();
    controller.abort();

    await expect(next).resolves.toEqual({ done: true, value: undefined });
    expect(canceled).toBe(true);
  });

  it("propagates reader failures and still releases the response reader", async () => {
    const failure = new Error("reader failed");
    const stream = new ReadableStream<Uint8Array>({
      pull() {
        throw failure;
      },
    });
    const events = parseSseResponse(new Response(stream));

    await expect(events.next()).rejects.toBe(failure);
    expect(stream.locked).toBe(false);
  });
});
