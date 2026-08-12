import { describe, expect, it } from "vitest";
import { createRouteGuard } from "./guards";
import { adminRoutes } from "../admin/routes";

function adminSettingsMeta() {
  const route = adminRoutes.find((item) => item.name === "admin-settings");
  if (!route) throw new Error("admin settings route is missing");
  return route.meta ?? {};
}

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

  it("暂时离线时保留已验证会话和原导航", async () => {
    const guard = createRouteGuard({
      auth: {
        state: {
          status: "offline",
          user: { id: 1, email: "student@example.com", roles: ["STUDENT"] },
          capabilities: null,
          error: Object.assign(new Error("Failed to fetch"), { code: "NETWORK_ERROR" }),
        },
        restoreSession: async () => undefined,
      } as never,
    });

    await expect(guard({ path: "/user/chapters", fullPath: "/user/chapters", meta: { requiresAuth: true } } as never)).resolves.toBe(true);
  });

  it("暂时离线时仍拒绝已知角色不足的管理路由", async () => {
    const guard = createRouteGuard({
      auth: {
        state: {
          status: "offline",
          user: { id: 1, email: "student@example.com", roles: ["STUDENT"] },
          capabilities: null,
          error: Object.assign(new Error("Failed to fetch"), { code: "NETWORK_ERROR" }),
        },
        restoreSession: async () => undefined,
      } as never,
    });

    await expect(guard({ path: "/admin", fullPath: "/admin", meta: { requiresAuth: true, roles: ["ADMIN"] } } as never))
      .resolves.toEqual({ name: "forbidden" });
  });

  it("冷启动离线且没有已保留用户时仍要求登录", async () => {
    const guard = createRouteGuard({
      auth: {
        state: {
          status: "offline",
          user: null,
          capabilities: null,
          error: Object.assign(new Error("Failed to fetch"), { code: "NETWORK_ERROR" }),
        },
        restoreSession: async () => undefined,
      } as never,
    });

    await expect(guard({ path: "/user/chapters", fullPath: "/user/chapters", meta: { requiresAuth: true } } as never))
      .resolves.toEqual({ name: "login", query: { redirect: "/user/chapters" } });
  });

  it("能力接口暂时不可用时不误跳转到 403", async () => {
    const guard = createRouteGuard({
      auth: {
        state: {
          status: "authenticated",
          user: { id: 1, email: "admin@example.com", roles: ["ADMIN"] },
          capabilities: null,
          error: Object.assign(new Error("unavailable"), { status: 503, code: "SERVICE_UNAVAILABLE" }),
        },
        restoreSession: async () => undefined,
        loadCapabilities: async () => null,
      } as never,
    });

    await expect(guard({ path: "/admin/settings", fullPath: "/admin/settings", meta: adminSettingsMeta() } as never)).resolves.toBe(true);
  });

  it.each([
    ["NOT_CONFIGURED", { available: false, status: "NOT_CONFIGURED", reason: "NOT_CONFIGURED" }],
    ["disabled configuration", { available: false, status: "UNAVAILABLE", reason: "PERSISTED_CONFIGURATION_DISABLED" }],
    ["zero quota", { available: false, status: "UNAVAILABLE", reason: "PERSISTED_QUOTA_NOT_CONFIGURED" }],
    ["model service unavailable", { available: false, status: "UNAVAILABLE", reason: "MODEL_CONFIG_UNAVAILABLE" }],
  ])("allows an ADMIN into settings when model settings are %s", async (_state, modelSettings) => {
    const guard = createRouteGuard({
      auth: {
        state: {
          status: "authenticated",
          user: { id: 1, email: "admin@example.com", roles: ["ADMIN"] },
          capabilities: {
            userId: 1,
            roles: ["ADMIN"],
            modules: { modelSettings },
            service: { name: "spring", version: "test", status: "AVAILABLE" },
          },
          error: null,
        },
        restoreSession: async () => undefined,
      } as never,
    });

    await expect(guard({ path: "/admin/settings", fullPath: "/admin/settings", meta: adminSettingsMeta() } as never)).resolves.toBe(true);
  });
});
