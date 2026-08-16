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
  return { router, wrapper: mount(AppShell, { attachTo: document.body, global: { plugins: [router] }, slots: { default: "<p>content</p>" } }) };
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
      { path: "/admin/mail", component: { template: "<div />" }, meta: { layout: "admin" } },
      { path: "/login", component: { template: "<div />" } },
    ],
  });
  await router.push("/admin");
  await router.isReady();
  authMock.state.user = { id: 1, email: "admin@example.com", roles: ["ADMIN"] };
  return { router, wrapper: mount(AppShell, { attachTo: document.body, global: { plugins: [router] }, slots: { default: "<p>content</p>" } }) };
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
    expect(nav.find("a[href=\"/admin/mail\"]").exists()).toBe(true);
    expect(wrapper.find("header nav[aria-label=\"管理端导航\"]").exists()).toBe(false);
    wrapper.unmount();
  });

  it("lets keyboard users dismiss the mobile admin navigation with Escape", async () => {
    const { wrapper } = await mountAdminShell();
    const toggle = wrapper.get(".admin-menu-toggle");

    await toggle.trigger("click");
    expect(toggle.attributes("aria-expanded")).toBe("true");
    expect(wrapper.find("#admin-mobile-navigation").exists()).toBe(true);

    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await wrapper.vm.$nextTick();

    expect(toggle.attributes("aria-expanded")).toBe("false");
    expect(wrapper.find("#admin-mobile-navigation").exists()).toBe(false);
    expect(document.activeElement).toBe(toggle.element);
    wrapper.unmount();
  });

  it("locks background scrolling while the mobile admin navigation is open", async () => {
    const previousBodyOverflow = document.body.style.overflow;
    const previousRootOverflow = document.documentElement.style.overflow;
    document.body.style.overflow = "auto";
    document.documentElement.style.overflow = "scroll";

    const { wrapper } = await mountAdminShell();
    try {
      await wrapper.get(".admin-menu-toggle").trigger("click");

      expect(document.body.style.overflow).toBe("hidden");
      expect(document.documentElement.style.overflow).toBe("hidden");

      await wrapper.get(".admin-mobile-nav-layer__close").trigger("click");

      expect(document.body.style.overflow).toBe("auto");
      expect(document.documentElement.style.overflow).toBe("scroll");
    } finally {
      wrapper.unmount();
      document.body.style.overflow = previousBodyOverflow;
      document.documentElement.style.overflow = previousRootOverflow;
    }
  });

  it("keeps Tab and Shift+Tab focus inside the mobile admin navigation", async () => {
    const { wrapper } = await mountAdminShell();
    try {
      await wrapper.get(".admin-menu-toggle").trigger("click");

      const closeButton = wrapper.get(".admin-mobile-nav-layer__close");
      const navLinks = wrapper.findAll("#admin-mobile-navigation a");
      const lastNavLink = navLinks[navLinks.length - 1];
      const closeButtonElement = closeButton.element as HTMLButtonElement;
      const lastNavLinkElement = lastNavLink.element as HTMLAnchorElement;
      lastNavLinkElement.focus();

      const tabEvent = new KeyboardEvent("keydown", { key: "Tab", bubbles: true, cancelable: true });
      window.dispatchEvent(tabEvent);

      expect(tabEvent.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(closeButtonElement);

      closeButtonElement.focus();
      const reverseTabEvent = new KeyboardEvent("keydown", { key: "Tab", shiftKey: true, bubbles: true, cancelable: true });
      window.dispatchEvent(reverseTabEvent);

      expect(reverseTabEvent.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(lastNavLinkElement);
    } finally {
      wrapper.unmount();
    }
  });

  it("keeps learning navigation in the header without rendering the admin sidebar", async () => {
    const { wrapper } = await mountShell();

    expect(wrapper.find("header nav[aria-label=\"学习端导航\"]").exists()).toBe(true);
    expect(wrapper.find("aside[data-layout=\"admin-sidebar\"]").exists()).toBe(false);
    expect(wrapper.get(".app-frame").classes()).not.toContain("app-frame--admin");
    wrapper.unmount();
  });
});
