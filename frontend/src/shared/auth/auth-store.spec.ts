import { describe, expect, it } from "vitest";
import { classifyAuthError, createAuthStore } from "./auth-store";

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

  it("会话恢复暂时离线时保留已验证的用户", async () => {
    let offline = false;
    const api = {
      request: async () => {
        if (offline) throw new TypeError("Failed to fetch");
        return { kind: "json", status: 200, data: { id: 7, email: "student@example.com", roles: ["STUDENT"] } };
      },
    } as never;
    const store = createAuthStore({ api });

    await store.restoreSession();
    offline = true;
    await store.restoreSession();

    expect(store.state.status).toBe("offline");
    expect(store.state.user).toMatchObject({ id: 7, roles: ["STUDENT"] });
  });

  it("会话恢复的 503 不清除已验证用户", async () => {
    let unavailable = false;
    const api = {
      request: async () => {
        if (unavailable) {
          const error = Object.assign(new Error("temporarily unavailable"), { status: 503, code: "SERVICE_UNAVAILABLE" });
          throw error;
        }
        return { kind: "json", status: 200, data: { id: 8, email: "student@example.com", roles: ["STUDENT"] } };
      },
    } as never;
    const store = createAuthStore({ api });

    await store.restoreSession();
    unavailable = true;
    await store.restoreSession();

    expect(store.state.status).toBe("error");
    expect(store.state.user).toMatchObject({ id: 8, roles: ["STUDENT"] });
  });

  it("403 保留已验证会话并进入明确的权限状态", async () => {
    let forbidden = false;
    const api = {
      request: async () => {
        if (forbidden) throw Object.assign(new Error("forbidden"), { status: 403, code: "ACCESS_DENIED" });
        return { kind: "json", status: 200, data: { id: 9, email: "student@example.com", roles: ["STUDENT"] } };
      },
    } as never;
    const store = createAuthStore({ api });

    await store.restoreSession();
    forbidden = true;
    await store.restoreSession();

    expect(store.state.status).toBe("forbidden");
    expect(store.state.user).toMatchObject({ id: 9 });
  });

  it("classifies stable API and transport failures without collapsing them into logout", () => {
    expect(classifyAuthError(Object.assign(new Error("unauthorized"), { status: 401 }))).toEqual({ kind: "unauthorized" });
    expect(classifyAuthError(Object.assign(new Error("forbidden"), { status: 403 }))).toEqual({ kind: "forbidden" });
    expect(classifyAuthError(Object.assign(new Error("missing"), { status: 404 }))).toEqual({ kind: "not-found" });
    expect(classifyAuthError(Object.assign(new Error("slow down"), { status: 429, headers: new Headers({ "Retry-After": "15" }) }))).toEqual({ kind: "rate-limited", retryAfterSeconds: 15 });
    expect(classifyAuthError(Object.assign(new Error("unavailable"), { status: 503 }))).toEqual({ kind: "unavailable" });
    expect(classifyAuthError(new TypeError("Failed to fetch"))).toEqual({ kind: "offline" });
    expect(classifyAuthError(Object.assign(new Error("server"), { status: 500 }))).toEqual({ kind: "server" });
    expect(classifyAuthError(Object.assign(new Error("aborted"), { name: "AbortError" }))).toEqual({ kind: "timeout" });
  });

  it("keeps the session but exposes an unavailable capability state when discovery cannot reach the service", async () => {
    const api = {
      request: async (path: string) => {
        if (path === "/admin/capabilities") throw new TypeError("Failed to fetch");
        return { kind: "json", status: 200, data: { id: 10, email: "admin@example.com", roles: ["ADMIN"] } };
      },
    } as never;
    const store = createAuthStore({ api });

    await store.restoreSession();
    await expect(store.loadCapabilities()).resolves.toBeNull();

    expect(store.state.status).toBe("offline");
    expect(store.state.user).toMatchObject({ id: 10, roles: ["ADMIN"] });
    expect(store.state.capabilityStatus).toBe("unavailable");
    expect(store.state.capabilityError).toBeInstanceOf(TypeError);
  });

  it("retries capability discovery after an outage without losing the retained session", async () => {
    let attempts = 0;
    const capability = {
      userId: 10,
      roles: ["ADMIN"],
      modules: { modelSettings: { available: true, status: "AVAILABLE" } },
      service: { name: "spring", version: "test", status: "AVAILABLE" },
    };
    const api = {
      request: async (path: string) => {
        if (path !== "/admin/capabilities") {
          return { kind: "json", status: 200, data: { id: 10, email: "admin@example.com", roles: ["ADMIN"] } };
        }
        attempts += 1;
        if (attempts === 1) throw new TypeError("Failed to fetch");
        return { kind: "json", status: 200, data: capability };
      },
    } as never;
    const store = createAuthStore({ api });

    await store.restoreSession();
    await expect(store.loadCapabilities()).resolves.toBeNull();
    await expect(store.loadCapabilities()).resolves.toEqual(capability);

    expect(attempts).toBe(2);
    expect(store.state.status).toBe("authenticated");
    expect(store.state.capabilityStatus).toBe("available");
    expect(store.state.capabilityError).toBeNull();
  });

  it("keeps an authenticated session when capability discovery is explicitly forbidden", async () => {
    const api = {
      request: async (path: string) => {
        if (path === "/admin/capabilities") throw Object.assign(new Error("forbidden"), { status: 403, code: "CAPABILITY_DENIED" });
        return { kind: "json", status: 200, data: { id: 11, email: "admin@example.com", roles: ["ADMIN"] } };
      },
    } as never;
    const store = createAuthStore({ api });

    await store.restoreSession();
    await expect(store.loadCapabilities()).resolves.toBeNull();

    expect(store.state.status).toBe("authenticated");
    expect(store.state.user).toMatchObject({ id: 11, roles: ["ADMIN"] });
    expect(store.state.error?.status).toBe(403);
    expect(store.state.capabilityStatus).toBe("forbidden");
  });
});
