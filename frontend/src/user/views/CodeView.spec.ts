import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { mockApi } = vi.hoisted(() => ({
  mockApi: {
    runCode: vi.fn(),
    analyzeCode: vi.fn(),
    getReadiness: vi.fn(),
  },
}));

vi.mock("../runtime", () => ({ userApi: mockApi }));

import CodeView from "./CodeView.vue";

const runResponse = {
  language: "c" as const,
  status: "success" as const,
  stdout: "hello, data structure!\n",
  stderr: "",
  durationMs: 12,
  runId: "run-1",
};

const analysisReady = {
  operation: "CODE_ANALYSIS" as const,
  evidenceRequired: false,
  modelAvailable: true,
  modelReason: "PERSISTED_CONFIGURATION_READY" as const,
  evidenceAvailable: false,
  evidenceReason: "CONTEXT_EVIDENCE_UNAVAILABLE" as const,
  currentContext: { chapterId: "stack", queryScoped: true },
  availableResourceCount: 0,
  availableKnowledgeChunkCount: 0,
  availableSourceCount: 0,
  excludedOrUnverifiedCount: 0,
  remainingDailyTokenQuota: 1000,
  quotaStatus: "AVAILABLE" as const,
  allowFormalGeneration: true,
  blockingReasons: [],
};

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/user/code", component: { template: "<div />" } },
      { path: "/user/chapters/:chapterId", component: { template: "<div />" } },
      { path: "/login", component: { template: "<div />" } },
    ],
  });
  await router.push("/user/code?chapterId=stack");
  await router.isReady();
  return mount(CodeView, {
    global: {
      plugins: [router],
      stubs: {
        UserFrame: { template: "<div><slot /><aside><slot name=\"rail\" /></aside></div>" },
      },
    },
  });
}

describe("CodeView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.runCode.mockResolvedValue(runResponse);
    mockApi.getReadiness.mockResolvedValue(analysisReady);
    mockApi.analyzeCode.mockResolvedValue({ analysis: "代码可以正常输出。" });
  });

  it("先显示空状态，并在沙箱执行期间显示加载状态", async () => {
    let resolveRun!: (value: typeof runResponse) => void;
    mockApi.runCode.mockImplementationOnce(() => new Promise((resolve) => { resolveRun = resolve; }));
    const wrapper = await mountView();

    expect(wrapper.get('[data-testid="code-result-state"]').classes()).toContain("user-state--empty");
    await wrapper.get("form").trigger("submit");
    await wrapper.vm.$nextTick();
    expect(wrapper.get('[data-testid="code-result-state"]').classes()).toContain("user-state--loading");

    resolveRun(runResponse);
    await flushPromises();
    expect(wrapper.get('[data-testid="code-run-result"]').text()).toContain("hello, data structure!");
  });

  it("使用已持久化运行编号进行分析，并先检查 Spring v1 分析就绪状态", async () => {
    const wrapper = await mountView();
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    await wrapper.get('[data-testid="code-analyze"]').trigger("click");
    await flushPromises();

    expect(mockApi.getReadiness).toHaveBeenCalledWith({ operation: "CODE_ANALYSIS", chapterId: "stack" });
    expect(mockApi.analyzeCode).toHaveBeenCalledWith({ runId: "run-1" });
    expect(wrapper.get('[data-testid="code-analysis"]').text()).toContain("代码可以正常输出。");
  });

  it("沙箱失败时显示可重试错误，并保留同一份输入重试", async () => {
    mockApi.runCode.mockRejectedValueOnce({ status: 503, message: "sandbox unavailable" });
    const wrapper = await mountView();

    await wrapper.get("form").trigger("submit");
    await flushPromises();
    const state = wrapper.get('[data-testid="code-result-state"]');
    expect(state.classes()).toContain("user-state--error");
    await state.get("button").trigger("click");
    await flushPromises();

    expect(mockApi.runCode).toHaveBeenCalledTimes(2);
    expect(wrapper.get('[data-testid="code-run-result"]').text()).toContain("hello, data structure!");
  });

  it("分析就绪检查要求登录时呈现权限状态，且不提交分析请求", async () => {
    mockApi.getReadiness.mockRejectedValueOnce({ status: 401, message: "login required" });
    const wrapper = await mountView();
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    await wrapper.get('[data-testid="code-analyze"]').trigger("click");
    await flushPromises();

    expect(wrapper.get('[data-testid="code-analysis-state"]').classes()).toContain("user-state--permission");
    expect(mockApi.analyzeCode).not.toHaveBeenCalled();
  });

  it("模型或配额未就绪时显示服务限制，并避免提交分析", async () => {
    mockApi.getReadiness.mockResolvedValueOnce({
      ...analysisReady,
      modelAvailable: false,
      quotaStatus: "NOT_CONFIGURED" as const,
      allowFormalGeneration: false,
      blockingReasons: ["PERSISTED_CONFIGURATION_DISABLED"],
    });
    const wrapper = await mountView();
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    await wrapper.get('[data-testid="code-analyze"]').trigger("click");
    await flushPromises();

    const state = wrapper.get('[data-testid="code-analysis-state"]');
    expect(state.classes()).toContain("user-state--error");
    expect(state.text()).toContain("代码分析模型当前不可用");
    expect(mockApi.analyzeCode).not.toHaveBeenCalled();
  });
});
