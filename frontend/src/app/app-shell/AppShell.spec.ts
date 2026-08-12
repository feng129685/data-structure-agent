import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import AppShell from "./AppShell.vue";

const authMock = vi.hoisted(() => ({
  state: {
    status: "offline" as string,
    user: { id: 7, email: "student@example.com", roles: ["STUDENT"] },
    capabilities: null,
    error: null,
  },
  logout: vi.fn(async () => undefined),
}));

vi.mock("../providers/runtime", () => ({ auth: authMock }));

async function mountShell() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div />" } },
      { path: "/user/chapters", component: { template: "<div />" } },
      { path: "/user/coach", component: { template: "<div />" } },
      { path: "/user/classroom", component: { template: "<div />" } },
      { path: "/user/animation", component: { template: "<div />" } },
      { path: "/login", component: { template: "<div />" } },
    ],
  });
  await router.push("/user/chapters");
  await router.isReady();
  return { router, wrapper: mount(AppShell, { global: { plugins: [router] }, slots: { default: "<p>content</p>" } }) };
}

describe("AppShell retained session presentation", () => {
  beforeEach(() => {
    authMock.state.status = "offline";
    authMock.state.user = { id: 7, email: "student@example.com", roles: ["STUDENT"] };
    authMock.logout.mockClear();
  });

  it.each(["offline", "error"])("keeps the logout action when a retained session is temporarily %s", async (status) => {
    authMock.state.status = status;
    const { wrapper } = await mountShell();

    expect(wrapper.text()).toContain("student@example.com");
    expect(wrapper.get("button").text()).toContain("退出");
    expect(wrapper.find("a[href=\"/login\"]").exists()).toBe(false);
    wrapper.unmount();
  });
});
