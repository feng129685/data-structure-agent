import { describe, expect, it, vi } from "vitest";
import { ApiClientError, createApiClient, parseSseStream } from "./client";

describe("API 客户端公共边界", () => {
  it("携带 credentials 和 request id，并保留 Spring 错误字段", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          code: "AUTH_REQUIRED",
          message: "请先登录",
          requestId: "req-42",
          details: ["session expired"],
        }),
        {
          status: 401,
          headers: { "content-type": "application/json", "x-request-id": "req-42" },
        },
      ),
    );
    const client = createApiClient({ fetcher });

    await expect(client.request("/users/me")).rejects.toMatchObject({
      status: 401,
      code: "AUTH_REQUIRED",
      requestId: "req-42",
      details: ["session expired"],
    });
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/users/me",
      expect.objectContaining({
        credentials: "include",
        headers: expect.objectContaining({ "X-Request-Id": expect.any(String) }),
      }),
    );
  });

  it("区分 204 空响应和二进制响应", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(new Uint8Array([1, 2, 3]), { status: 200 }));
    const client = createApiClient({ fetcher });

    await expect(client.request("/auth/logout", { method: "POST" })).resolves.toMatchObject({
      kind: "empty",
      status: 204,
    });
    await expect(client.request("/resources/file", {}, { responseType: "binary" })).resolves.toMatchObject({
      kind: "binary",
      status: 200,
    });
  });

  it("cancels the reader when the stream abort signal fires", async () => {
    let canceled = false;
    const stream = new ReadableStream<Uint8Array>({
      cancel() {
        canceled = true;
      },
    });
    const controller = new AbortController();
    const events = parseSseStream(stream, { signal: controller.signal });

    const next = events.next();
    controller.abort();

    await expect(next).resolves.toEqual({ done: true, value: undefined });
    expect(canceled).toBe(true);
  });
});
