import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { mockApi } = vi.hoisted(() => ({
  mockApi: {
    getLearningProgress: vi.fn(),
    recordLearningEvent: vi.fn(),
  },
}));

vi.mock("../runtime", () => ({ userApi: mockApi }));

import ProgressView from "./ProgressView.vue";

const progress = {
  totalActivities: 8,
  chapters: [{
    chapterId: "stack",
    chapterNumber: 1,
    title: "栈",
    chatCount: 2,
    classroomCount: 1,
    animationCount: 1,
    codeRunCount: 2,
    eventCount: 2,
    totalActivities: 8,
    lastActivityAt: "2026-08-12T00:00:00Z",
  }],
};

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/user/progress", component: { template: "<div />" } },
      { path: "/user/chapters/:chapterId", component: { template: "<div />" } },
      { path: "/login", component: { template: "<div />" } },
    ],
  });
  await router.push("/user/progress");
  await router.isReady();
  return mount(ProgressView, {
    global: {
      plugins: [router],
      stubs: {
        UserFrame: { template: "<div><slot /><aside><slot name=\"rail\" /></aside></div>" },
      },
    },
  });
}

describe("ProgressView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.getLearningProgress.mockResolvedValue(progress);
    mockApi.recordLearningEvent.mockResolvedValue({
      id: 1,
      eventType: "REVIEW_COMPLETED",
      chapterId: null,
      referenceId: null,
      createdAt: "2026-08-12T00:00:00Z",
    });
  });

  it("加载真实进度并记录总复盘后重新读取聚合数据", async () => {
    const wrapper = await mountView();
    await flushPromises();

    expect(wrapper.get('[data-testid="progress-list"]').text()).toContain("第 1 章 栈");
    await wrapper.get('[data-testid="progress-complete-all"]').trigger("click");
    await flushPromises();

    expect(mockApi.recordLearningEvent).toHaveBeenCalledWith({ eventType: "REVIEW_COMPLETED", chapterId: null });
    expect(mockApi.getLearningProgress).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("复盘完成已记录");
  });

  it("进度接口返回空章节时明确显示空状态", async () => {
    mockApi.getLearningProgress.mockResolvedValueOnce({ totalActivities: 0, chapters: [] });
    const wrapper = await mountView();
    await flushPromises();

    expect(wrapper.get('[data-testid="progress-empty-state"]').classes()).toContain("user-state--empty");
  });

  it("保存复盘被拒绝时显示权限状态，并引导登录而不重试受限操作", async () => {
    mockApi.recordLearningEvent.mockRejectedValueOnce({ status: 403, message: "forbidden" });
    const wrapper = await mountView();
    await flushPromises();

    await wrapper.get('[data-testid="progress-complete-stack"]').trigger("click");
    await flushPromises();
    const state = wrapper.get('[data-testid="progress-save-state"]');
    expect(state.classes()).toContain("user-state--permission");
    expect(state.get("a").attributes("href")).toBe("/login");
    expect(mockApi.recordLearningEvent).toHaveBeenCalledTimes(1);
  });

  it("保存临时失败时保留同一章节范围供用户重新提交", async () => {
    mockApi.recordLearningEvent
      .mockRejectedValueOnce({ status: 503, message: "service unavailable" })
      .mockResolvedValueOnce({
        id: 2,
        eventType: "REVIEW_COMPLETED",
        chapterId: "stack",
        referenceId: null,
        createdAt: "2026-08-12T00:01:00Z",
      });
    const wrapper = await mountView();
    await flushPromises();

    await wrapper.get('[data-testid="progress-complete-stack"]').trigger("click");
    await flushPromises();
    await wrapper.get('[data-testid="progress-save-state"] button').trigger("click");
    await flushPromises();

    expect(mockApi.recordLearningEvent).toHaveBeenNthCalledWith(1, { eventType: "REVIEW_COMPLETED", chapterId: "stack" });
    expect(mockApi.recordLearningEvent).toHaveBeenNthCalledWith(2, { eventType: "REVIEW_COMPLETED", chapterId: "stack" });
  });

  it("刷新期间保留上次成功进度，并给出刷新中的状态", async () => {
    const wrapper = await mountView();
    await flushPromises();
    let resolveProgress!: (value: typeof progress) => void;
    mockApi.getLearningProgress.mockImplementationOnce(() => new Promise((resolve) => { resolveProgress = resolve; }));

    await wrapper.get('[data-testid="progress-refresh"]').trigger("click");
    await wrapper.vm.$nextTick();

    expect(wrapper.get('[data-testid="progress-list"]').text()).toContain("第 1 章 栈");
    expect(wrapper.get('[data-testid="progress-refreshing"]').text()).toContain("正在刷新");

    resolveProgress(progress);
    await flushPromises();
  });

  it("首次读取被拒绝时使用权限状态而不显示虚构进度", async () => {
    mockApi.getLearningProgress.mockRejectedValueOnce({ status: 401, message: "login required" });
    const wrapper = await mountView();
    await flushPromises();

    expect(wrapper.get('[data-testid="progress-load-state"]').classes()).toContain("user-state--permission");
    expect(wrapper.find('[data-testid="progress-list"]').exists()).toBe(false);
  });

  it("刷新后失去访问权限时移除旧进度并切换到权限状态", async () => {
    const wrapper = await mountView();
    await flushPromises();
    mockApi.getLearningProgress.mockRejectedValueOnce({ status: 401, message: "login required" });

    await wrapper.get('[data-testid="progress-refresh"]').trigger("click");
    await flushPromises();

    expect(wrapper.get('[data-testid="progress-load-state"]').classes()).toContain("user-state--permission");
    expect(wrapper.find('[data-testid="progress-list"]').exists()).toBe(false);
  });
});
