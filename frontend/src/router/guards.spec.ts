import { describe, expect, it } from "vitest";
import { createRouteGuard } from "./guards";

describe("路由守卫公共边界", () => {
  it("未登录访问受保护路由时保留回跳地址", async () => {
    const guard = createRouteGuard({
      auth: {
        state: { status: "anonymous", user: null, capabilities: null, error: null },
        restoreSession: async () => undefined,
      } as never,
    });
    const result = await guard({ path: "/user/chapters", fullPath: "/user/chapters?chapter=03", meta: { requiresAuth: true } } as never);
    expect(result).toMatchObject({ name: "login", query: { redirect: "/user/chapters?chapter=03" } });
  });

  it("角色不足时进入 403，而不是只隐藏导航", async () => {
    const guard = createRouteGuard({
      auth: {
        state: { status: "authenticated", user: { id: 1, email: "student@example.com", roles: ["STUDENT"] }, capabilities: null, error: null },
        restoreSession: async () => undefined,
      } as never,
    });
    const result = await guard({ path: "/admin", fullPath: "/admin", meta: { requiresAuth: true, roles: ["ADMIN"] } } as never);
    expect(result).toEqual({ name: "forbidden" });
  });
});
