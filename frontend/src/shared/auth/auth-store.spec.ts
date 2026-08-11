import { describe, expect, it } from "vitest";
import { createAuthStore } from "./auth-store";

describe("鉴权状态公共边界", () => {
  it("刷新时恢复当前用户，401 变成匿名状态", async () => {
    const api = {
      request: async () => ({ kind: "json", status: 200, data: { id: 7, email: "student@example.com", roles: ["STUDENT"] } }),
    } as never;
    const store = createAuthStore({ api });

    await store.restoreSession();
    expect(store.state.status).toBe("authenticated");
    expect(store.state.user?.roles).toContain("STUDENT");

    const anonymousApi = {
      request: async () => {
        const error = new Error("unauthorized") as Error & { status: number; code: string };
        error.status = 401;
        error.code = "AUTH_REQUIRED";
        throw error;
      },
    } as never;
    const anonymous = createAuthStore({ api: anonymousApi });
    await anonymous.restoreSession();
    expect(anonymous.state.status).toBe("anonymous");
    expect(anonymous.state.user).toBeNull();
  });

  it("将禁用账号保持为不可用会话，而不是当作匿名用户", async () => {
    const api = {
      request: async () => {
        const error = new Error("disabled") as Error & { status: number; code: string };
        error.status = 403;
        error.code = "AUTH_USER_DISABLED";
        throw error;
      },
    } as never;
    const store = createAuthStore({ api });
    await store.restoreSession();
    expect(store.state.status).toBe("disabled");
    expect(store.state.error?.code).toBe("AUTH_USER_DISABLED");
  });
});
