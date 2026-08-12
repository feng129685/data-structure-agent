import { describe, expect, it, vi } from "vitest";

const post = vi.fn(async () => ({ kind: "json", data: { connected: false, code: "CONNECTION_FAILED" } }));
const get = vi.fn(async () => ({ kind: "json", data: { available: false, reason: "NOT_CONFIGURED", configuration: null } }));

vi.mock("../app/providers/runtime", () => ({ api: { get, post, put: vi.fn(), patch: vi.fn() } }));

describe("admin API contract", async () => {
  const { adminApi } = await import("./api");

  it("reads model settings without treating NOT_CONFIGURED as a transport error", async () => {
    await expect(adminApi.getModelConfig()).resolves.toMatchObject({ available: false, reason: "NOT_CONFIGURED", configuration: null });
    expect(get).toHaveBeenCalledWith("/admin/model-config");
  });

  it("calls the connection test endpoint without a request body", async () => {
    await adminApi.testModelConnection();
    expect(post).toHaveBeenCalledWith("/admin/model-config/test");
    expect(post.mock.calls[0]).toHaveLength(1);
  });
});
