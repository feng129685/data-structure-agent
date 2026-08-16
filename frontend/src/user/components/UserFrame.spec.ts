import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import UserFrame from "./UserFrame.vue";

describe("UserFrame", () => {
  it("提供跳到主要学习内容的键盘入口", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        "/user/home",
        "/user/chapters",
        "/user/knowledge",
        "/user/coach",
        "/user/classroom",
        "/user/animation",
        "/user/code",
        "/user/progress",
        "/user/profile",
      ].map((path) => ({ path, component: { template: "<div />" } })),
    });
    await router.push("/user/home");
    await router.isReady();
    const wrapper = mount(UserFrame, {
      slots: { default: "<h1>当前内容</h1>" },
      global: { plugins: [router] },
    });

    expect(wrapper.get(".user-skip-link").attributes("href")).toBe("#user-learning-content");
    expect(wrapper.get("main").attributes("id")).toBe("user-learning-content");
  });

  it("不在侧栏或移动导航中暴露算法舞台入口", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        "/user/home",
        "/user/chapters",
        "/user/knowledge",
        "/user/coach",
        "/user/classroom",
        "/user/animation",
        "/user/code",
        "/user/progress",
        "/user/profile",
      ].map((path) => ({ path, component: { template: "<div />" } })),
    });
    await router.push("/user/home");
    await router.isReady();
    const wrapper = mount(UserFrame, {
      global: { plugins: [router] },
    });

    const navigationLinks = wrapper.findAll("nav a");
    expect(navigationLinks.map((link) => link.text())).not.toContain("算法舞台");
    expect(navigationLinks.map((link) => link.attributes("href"))).not.toContain("/user/animation");
  });
});
