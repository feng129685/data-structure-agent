import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { mockApi } = vi.hoisted(() => ({
  mockApi: {
    simulateAnimation: vi.fn(),
    generateAnimation: vi.fn(),
    saveObservation: vi.fn(),
  },
}));

vi.mock("../runtime", () => ({ userApi: mockApi }));

import AnimationView from "./AnimationView.vue";

const deterministicResponse = {
  protocol: "dsvp/1.0" as const,
  request: {
    version: "1.0" as const,
    structure: "stack" as const,
    operation: "push",
    params: { value: 8 },
    initial_state: { data: [] },
    chapterId: "stack",
  },
  trace: {},
  animationData: {
    animation: true as const,
    type: "stack" as const,
    title: "压栈",
    description: "将 8 压入栈顶",
    initial: [],
    steps: [{ op: "push", label: "压栈 8", note: "栈顶变为 8", value: 8 }],
  },
  evidencePersisted: true,
  animationRecordId: "animation-1",
  resolvedChapterId: "stack",
  matchSource: "EXPLICIT_CHAPTER" as const,
};

async function mountView(path = "/user/animation?chapterId=stack&from=chapter") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/user/animation", component: { template: "<div />" } },
      { path: "/user/chapters", component: { template: "<div />" } },
      { path: "/user/chapters/:chapterId", component: { template: "<div />" } },
      { path: "/user/home", component: { template: "<div />" } },
      { path: "/user/coach", component: { template: "<div />" } },
      { path: "/user/classroom", component: { template: "<div />" } },
    ],
  });
  await router.push(path);
  await router.isReady();
  return mount(AnimationView, {
    global: {
      plugins: [router],
      stubs: { UserFrame: { template: "<div><slot /><slot name=\"rail\" /></div>" } },
    },
  });
}

describe("AnimationView", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => vi.unstubAllGlobals());

  it("提交确定性 DSVP 请求并展示服务端返回的演示", async () => {
    mockApi.simulateAnimation.mockResolvedValue(deterministicResponse);
    const wrapper = await mountView();

    await wrapper.get('[data-testid="animation-operation"]').setValue("push");
    await wrapper.get('[data-testid="animation-value"]').setValue("8");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(mockApi.simulateAnimation).toHaveBeenCalledWith(expect.objectContaining({
      version: "1.0",
      structure: "stack",
      operation: "push",
      params: expect.objectContaining({ value: 8 }),
      chapterId: "stack",
      source_ref: "api/v1/animations/simulate",
      context: {
        chapter_id: "stack",
        source_type: "API",
        source_ref: "api/v1/animations/simulate",
      },
    }));
    expect(wrapper.text()).toContain("压栈");
    expect(wrapper.text()).toContain("本次观察");
  });

  it.each([
    ["chapter", "/user/chapters/stack"],
    ["coach", "/user/coach?chapterId=stack"],
    ["home", "/user/home"],
  ])("从 %s 学习来源进入时返回对应学习位置", async (from, expectedHref) => {
    const wrapper = await mountView(`/user/animation?chapterId=stack&from=${from}`);

    expect(wrapper.get('[data-testid="animation-return"]').attributes("href")).toBe(expectedHref);
    expect(wrapper.get('[data-testid="animation-run"]').attributes("disabled")).toBeUndefined();
  });

  it("从课堂真实会话进入时保留会话并提交课堂 DSVP 上下文", async () => {
    mockApi.simulateAnimation.mockResolvedValue(deterministicResponse);
    const sessionId = "classroom-session-1";
    const wrapper = await mountView(`/user/animation?chapterId=stack&from=classroom&sessionId=${sessionId}`);

    expect(wrapper.get('[data-testid="animation-return"]').attributes("href")).toBe(`/user/classroom?chapterId=stack&sessionId=${sessionId}`);
    expect(wrapper.text()).toContain("返回课堂");

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(mockApi.simulateAnimation).toHaveBeenCalledWith(expect.objectContaining({
      chapterId: "stack",
      classroomSessionId: sessionId,
      source_ref: sessionId,
      context: {
        chapter_id: "stack",
        classroom_session_id: sessionId,
        source_type: "CLASSROOM",
        source_ref: sessionId,
      },
    }));
  });

  it.each([
    ["缺少会话", "/user/animation?chapterId=stack&from=classroom"],
    ["会话超过 160 字符", `/user/animation?chapterId=stack&from=classroom&sessionId=${"a".repeat(161)}`],
  ])("课堂来源%s时阻止模拟并返回课堂", async (_label, path) => {
    const wrapper = await mountView(path);

    expect(wrapper.text()).toContain("课堂来源缺少有效会话");
    expect(wrapper.get('[data-testid="animation-context-return"]').attributes("href")).toBe("/user/classroom?chapterId=stack");
    expect(wrapper.text()).toContain("返回课堂");
    expect(wrapper.find('[data-testid="animation-run"]').exists()).toBe(false);
    expect(mockApi.simulateAnimation).not.toHaveBeenCalled();
  });

  it("问答来源保留章节 API 上下文且不伪造课堂会话", async () => {
    mockApi.simulateAnimation.mockResolvedValue(deterministicResponse);
    const wrapper = await mountView("/user/animation?chapterId=stack&from=coach");

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const request = mockApi.simulateAnimation.mock.calls[0][0];
    expect(request).toMatchObject({
      chapterId: "stack",
      source_ref: "api/v1/animations/simulate",
      context: {
        chapter_id: "stack",
        source_type: "API",
        source_ref: "api/v1/animations/simulate",
      },
    });
    expect(request).not.toHaveProperty("classroomSessionId");
  });

  it.each([
    ["缺少学习来源", "/user/animation?chapterId=stack", "/user/chapters/stack"],
    ["缺少章节范围", "/user/animation?from=chapter", "/user/chapters"],
    ["学习来源无效", "/user/animation?chapterId=stack&from=search", "/user/chapters/stack"],
  ])("%s 时阻止模拟并提供中文返回入口", async (_label, path, expectedHref) => {
    const wrapper = await mountView(path);

    expect(wrapper.text()).toContain("学习上下文不可用");
    expect(wrapper.text()).toContain("算法舞台不能单独打开");
    expect(wrapper.get('[data-testid="animation-context-return"]').attributes("href")).toBe(expectedHref);
    expect(wrapper.find('[data-testid="animation-run"]').exists()).toBe(false);
    expect(mockApi.simulateAnimation).not.toHaveBeenCalled();
  });

  it("为已持久化动画记录观察内容", async () => {
    mockApi.simulateAnimation.mockResolvedValue(deterministicResponse);
    mockApi.saveObservation.mockResolvedValue({ recordId: "animation-1", observation: "后进先出" });
    const wrapper = await mountView();

    await wrapper.get("form").trigger("submit");
    await flushPromises();
    await wrapper.get('[data-testid="animation-observation"]').setValue("后进先出");
    await wrapper.get('[data-testid="animation-save-observation"]').trigger("click");
    await flushPromises();

    expect(mockApi.saveObservation).toHaveBeenCalledWith("animation-1", { observation: "后进先出" });
    expect(wrapper.text()).toContain("观察已保存");
  });

  it("遵循减少动态偏好，仅提供手动单步控制", async () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true })));
    mockApi.simulateAnimation.mockResolvedValue(deterministicResponse);
    const wrapper = await mountView();

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("仅支持手动单步查看");
    expect(wrapper.findAll("button").some((button) => button.text() === "播放")).toBe(false);
  });
});
