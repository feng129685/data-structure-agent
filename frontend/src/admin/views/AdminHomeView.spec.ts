import { describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import AdminHomeView from "./AdminHomeView.vue";

const capabilities = vi.hoisted(() => vi.fn(async () => ({
  userId: 9,
  roles: ["ADMIN"],
  service: { name: "spring", version: "1.0.18", status: "AVAILABLE" },
  modules: {
    users: { available: true, status: "AVAILABLE" },
    reviewQueue: { available: true, status: "AVAILABLE" },
    backgroundTasks: { available: true, status: "AVAILABLE" },
    audit: { available: true, status: "AVAILABLE" },
    modelSettings: { available: false, status: "NOT_CONFIGURED", reason: "NOT_CONFIGURED" },
    mailSettings: { available: false, status: "UNAVAILABLE", reason: "MAIL_CONFIG_UNAVAILABLE" },
  },
})));

vi.mock("../api", () => ({
  adminApi: { capabilities },
  adminErrorMessage: () => "读取管理能力未完成（NETWORK_ERROR）。",
}));

describe("AdminHomeView", () => {
  it("keeps model settings reachable when the real capability says NOT_CONFIGURED", async () => {
    const component = { template: "<div />" };
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: "/admin", component },
      { path: "/admin/users", component },
      { path: "/admin/reviews", component },
      { path: "/admin/tasks", component },
      { path: "/admin/audit", component },
      { path: "/admin/settings", component },
      { path: "/admin/mail", component },
    ] });
    await router.push("/admin");
    await router.isReady();
    const wrapper = mount(AdminHomeView, { global: { plugins: [router] } });
    await flushPromises();

    expect(capabilities).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("尚未完成服务端配置");
    expect(wrapper.get("a[href='/admin/settings']").text()).toContain("进入模型配置");
    expect(wrapper.get("a[href='/admin/mail']").text()).toContain("进入邮件发送设置");
    expect(wrapper.text()).toContain("邮件配置服务暂时不可用");
    expect(wrapper.text()).not.toMatch(/总用户|待审核|任务总数/);
  });
});
