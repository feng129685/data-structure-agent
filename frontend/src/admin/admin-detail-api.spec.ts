import { describe, expect, it, vi } from "vitest";

const get = vi.fn(async (path: string) => ({
  kind: "json" as const,
  data: path.includes("background-tasks")
    ? { id: 11, status: "PENDING" }
    : { id: 7, email: "admin@example.edu" },
}));

vi.mock("../app/providers/runtime", () => ({
  api: { get, post: vi.fn(), put: vi.fn(), patch: vi.fn() },
}));

describe("admin detail API contract", async () => {
  const { adminApi } = await import("./api");

  it("uses the frozen v1 user detail path", async () => {
    await adminApi.user(7);
    expect(get).toHaveBeenCalledWith("/admin/users/7");
  });

  it("uses the frozen v1 background-task detail path", async () => {
    await adminApi.task(11);
    expect(get).toHaveBeenCalledWith("/admin/background-tasks/11");
  });
});
