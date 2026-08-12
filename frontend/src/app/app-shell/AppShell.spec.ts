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

async function mountAdminShell() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div />" } },
      { path: "/admin", component: { template: "<div />" }, meta: { layout: "admin" } },
      { path: "/admin/users", component: { template: "<div />" }, meta: { layout: "admin" } },
      { path: "/admin/reviews", component: { template: "<div />" }, meta: { layout: "admin" } },
      { path: "/admin/tasks", component: { template: "<div />" }, meta: { layout: "admin" } },
      { path: "/admin/audit", component: { template: "<div />" }, meta: { layout: "admin" } },
      { path: "/admin/settings", component: { template: "<div />" }, meta: { layout: "admin" } },
      { path: "/login", component: { template: "<div />" } },
    ],
  });
  await router.push("/admin");
  await router.isReady();
  authMock.state.user = { id: 1, email: "admin@example.com", roles: ["ADMIN"] };
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

  it("exposes every admin work area from the admin navigation", async () => {
    const { wrapper } = await mountAdminShell();

    const sidebar = wrapper.get("aside[data-layout=\"admin-sidebar\"]");
    const nav = sidebar.get("nav[aria-label=\"管理端导航\"]");
    expect(nav.find("a[href=\"/admin\"]").exists()).toBe(true);
    expect(nav.find("a[href=\"/admin/users\"]").exists()).toBe(true);
    expect(nav.find("a[href=\"/admin/reviews\"]").exists()).toBe(true);
    expect(nav.find("a[href=\"/admin/tasks\"]").exists()).toBe(true);
    expect(nav.find("a[href=\"/admin/audit\"]").exists()).toBe(true);
    expect(nav.find("a[href=\"/admin/settings\"]").exists()).toBe(true);
    expect(wrapper.find("header nav[aria-label=\"管理端导航\"]").exists()).toBe(false);
    wrapper.unmount();
  });

  it("keeps learning navigation in the header without rendering the admin sidebar", async () => {
    const { wrapper } = await mountShell();

    expect(wrapper.find("header nav[aria-label=\"学习端导航\"]").exists()).toBe(true);
    expect(wrapper.find("aside[data-layout=\"admin-sidebar\"]").exists()).toBe(false);
    expect(wrapper.get(".app-frame").classes()).not.toContain("app-frame--admin");
    wrapper.unmount();
  });
});
