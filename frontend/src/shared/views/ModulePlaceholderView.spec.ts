import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import ModulePlaceholderView from "./ModulePlaceholderView.vue";

const authMock = vi.hoisted(() => ({
  state: {
    status: "authenticated" as string,
    user: { id: 7, email: "admin@example.com", roles: ["ADMIN"] },
    capabilities: null,
    capabilityStatus: "unavailable" as string,
    capabilityError: Object.assign(new Error("unavailable"), { status: 503 }) as (Error & { status?: number }) | null,
    error: Object.assign(new Error("unavailable"), { status: 503 }) as (Error & { status?: number }) | null,
  },
  loadCapabilities: vi.fn(async () => null),
  restoreSession: vi.fn(async () => undefined),
}));

vi.mock("../../app/providers/runtime", () => ({ auth: authMock }));

async function mountModule(meta: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/module", component: ModulePlaceholderView, meta }],
  });
  await router.push("/module");
  await router.isReady();
  return mount(ModulePlaceholderView, { global: { plugins: [router] } });
}

describe("module capability and retained-session states", () => {
  beforeEach(() => {
    authMock.state.status = "authenticated";
    authMock.state.user = { id: 7, email: "admin@example.com", roles: ["ADMIN"] };
    authMock.state.capabilities = null;
    authMock.state.capabilityStatus = "unavailable";
    authMock.state.capabilityError = Object.assign(new Error("unavailable"), { status: 503 });
    authMock.state.error = authMock.state.capabilityError;
    authMock.loadCapabilities.mockClear();
    authMock.restoreSession.mockClear();
  });

  it("keeps the requested module visible and offers a real capability retry after a 503", async () => {
    const wrapper = await mountModule({
      requiresAuth: true,
      roles: ["ADMIN"],
      layout: "admin",
      module: "模型设置",
      requiresCapability: "modelSettings",
    });

    expect(wrapper.get("h1").text()).toBe("模型设置");
    expect(wrapper.get('[role="alert"]').text()).toContain("服务暂时不可用");
    await wrapper.get("button").trigger("click");
    await flushPromises();
    expect(authMock.loadCapabilities).toHaveBeenCalledTimes(1);
  });

  it("labels a retained offline user as offline instead of a guest", async () => {
    authMock.state.status = "offline";
    authMock.state.user = { id: 8, email: "student@example.com", roles: ["STUDENT"] };
    authMock.state.capabilityStatus = "unknown";
    authMock.state.capabilityError = null;
    authMock.state.error = new TypeError("Failed to fetch");

    const wrapper = await mountModule({ requiresAuth: true, layout: "shell", module: "章节与资源" });

    expect(wrapper.get('[role="status"]').text()).toContain("会话已保留");
    expect(wrapper.text()).not.toContain("访客模式");
    await wrapper.get("button").trigger("click");
    expect(authMock.restoreSession).toHaveBeenCalledTimes(1);
  });
});
