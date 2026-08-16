import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { mockApi } = vi.hoisted(() => ({
  mockApi: {
    getReadiness: vi.fn(),
    listChatSessions: vi.fn(),
  },
}));

vi.mock("../runtime", () => ({ userApi: mockApi }));

import CoachView from "./CoachView.vue";

const blockedReadiness = {
  operation: "CHAT" as const,
  evidenceRequired: true,
  modelAvailable: false,
  modelReason: "PERSISTED_CONFIGURATION_DISABLED" as const,
  evidenceAvailable: true,
  evidenceReason: null,
  currentContext: { chapterId: "stack", queryScoped: true },
  availableResourceCount: 2,
  availableKnowledgeChunkCount: 5,
  availableSourceCount: 2,
  excludedOrUnverifiedCount: 1,
  remainingDailyTokenQuota: 800,
  quotaStatus: "AVAILABLE" as const,
  allowFormalGeneration: false,
  blockingReasons: ["当前模型配置已停用"],
};

describe("CoachView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.getReadiness.mockResolvedValue(blockedReadiness);
    mockApi.listChatSessions.mockResolvedValue([]);
  });

  it("向学习者展示服务端 readiness 的阻断说明", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/user/coach", component: { template: "<div />" } },
        { path: "/user/animation", component: { template: "<div />" } },
      ],
    });
    await router.push("/user/coach?chapterId=stack");
    await router.isReady();
    const wrapper = mount(CoachView, {
      global: {
        plugins: [router],
        stubs: { UserFrame: { template: "<div><slot /><slot name=\"rail\" /></div>" } },
      },
    });
    await flushPromises();

    expect(mockApi.getReadiness).toHaveBeenCalledWith({ operation: "CHAT", chapterId: "stack" });
    expect(wrapper.text()).toContain("当前模型配置已停用");
    expect(wrapper.text()).toContain("模型不可用");
  });
});
