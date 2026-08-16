import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { mockApi } = vi.hoisted(() => ({
  mockApi: {
    getReadiness: vi.fn(),
    streamChat: vi.fn(),
    listChatSessions: vi.fn(),
    getChatSession: vi.fn(),
    deleteChatSession: vi.fn(),
  },
}));

vi.mock("../runtime", () => ({ userApi: mockApi }));

import ChatView from "./ChatView.vue";

const allowedReadiness = {
  operation: "CHAT" as const,
  evidenceRequired: true,
  modelAvailable: true,
  modelReason: "PERSISTED_CONFIGURATION_READY" as const,
  evidenceAvailable: true,
  evidenceReason: null,
  currentContext: { chapterId: "stack", queryScoped: true },
  availableResourceCount: 1,
  availableKnowledgeChunkCount: 1,
  availableSourceCount: 1,
  excludedOrUnverifiedCount: 0,
  remainingDailyTokenQuota: 1000,
  quotaStatus: "AVAILABLE" as const,
  allowFormalGeneration: true,
  blockingReasons: [],
};

function stream(events: Array<{ event: string; parsed?: unknown; data?: string }>) {
  return (async function*() {
    for (const event of events) yield { data: "", ...event };
  })();
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/user/coach", component: { template: "<div />" } },
      { path: "/user/animation", component: { template: "<div />" } },
    ],
  });
  await router.push("/user/coach?chapterId=stack");
  await router.isReady();
  return mount(ChatView, {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: {
        UserFrame: { template: "<div><slot /><slot name=\"rail\" /></div>" },
      },
    },
  });
}

describe("ChatView", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.clearAllMocks();
    mockApi.getReadiness.mockResolvedValue(allowedReadiness);
    mockApi.listChatSessions.mockResolvedValue([]);
  });
  afterEach(() => { document.body.innerHTML = ""; });

  it("在 readiness 通过后消费 sources、delta 与 done 事件", async () => {
    mockApi.streamChat.mockResolvedValue({
      events: stream([
        { event: "sources", parsed: { sources: [{ id: "source-1", chapterId: "stack", title: "栈", content: "后进先出", source: "教材", pageLabel: "第 3 页", score: 0.9, evidenceHash: "hash-1" }] } },
        { event: "delta", parsed: { content: "栈遵循" } },
        { event: "done", parsed: { answer: "栈遵循后进先出。", sessionId: "session-1", sources: [{ id: "source-1", chapterId: "stack", title: "栈", content: "后进先出", source: "教材", pageLabel: "第 3 页", score: 0.9, evidenceHash: "hash-1" }], persisted: true } },
      ]),
    });
    const wrapper = await mountView();
    await flushPromises();

    await wrapper.get('[data-testid="chat-prompt"]').setValue("栈是什么？");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(mockApi.getReadiness).toHaveBeenLastCalledWith({ operation: "CHAT", chapterId: "stack", prompt: "栈是什么？" });
    expect(mockApi.streamChat).toHaveBeenCalledWith({ prompt: "栈是什么？", chapterId: "stack" }, expect.any(AbortSignal));
    expect(wrapper.text()).toContain("栈遵循后进先出。");
    expect(wrapper.text()).toContain("后进先出");
    expect(wrapper.get('[data-testid="chat-animation"]').attributes("href")).toBe("/user/animation?chapterId=stack&from=coach");
    const generatedMessage = wrapper.get('[data-role="assistant"][data-state="complete"]');
    expect(generatedMessage.find("time").exists()).toBe(false);
    expect(generatedMessage.text()).not.toContain("模型");
    expect(mockApi.listChatSessions).toHaveBeenCalledTimes(2);
  });

  it("响应 SSE error 后允许重试同一问题", async () => {
    mockApi.streamChat
      .mockResolvedValueOnce({ events: stream([{ event: "error", parsed: { code: "MODEL_NOT_CONFIGURED", message: "模型暂不可用" } }]) })
      .mockResolvedValueOnce({ events: stream([{ event: "done", parsed: { answer: "恢复后的回答", sources: [], persisted: false } }]) });
    const wrapper = await mountView();
    await flushPromises();

    await wrapper.get('[data-testid="chat-prompt"]').setValue("解释队列");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("模型暂不可用");
    await wrapper.get('[data-testid="chat-retry"]').trigger("click");
    await flushPromises();

    expect(mockApi.streamChat).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("恢复后的回答");
  });

  it("打开历史会话时展示服务端返回的发送时间，且不虚构模型身份", async () => {
    mockApi.listChatSessions.mockResolvedValue([{ id: "session-1", chapterId: "stack", title: "栈的练习", updatedAt: "2026-08-12T00:00:00Z", messageCount: 2 }]);
    mockApi.getChatSession.mockResolvedValue({
      id: "session-1",
      chapterId: "stack",
      title: "栈的练习",
      updatedAt: "2026-08-12T00:00:00Z",
      messages: [{ id: 1, role: "assistant", content: "历史答案", sources: [], createdAt: "2026-08-12T00:00:00Z" }],
    });
    const wrapper = await mountView();
    await flushPromises();

    await wrapper.get('[data-testid="chat-session"]').trigger("click");
    await flushPromises();
    expect(mockApi.getChatSession).toHaveBeenCalledWith("session-1");
    expect(wrapper.text()).toContain("历史答案");
    const historyMessage = wrapper.get('[data-role="assistant"]');
    const historyTime = historyMessage.get("time");
    expect(historyTime.attributes("datetime")).toBe("2026-08-12T00:00:00Z");
    expect(historyTime.text()).toContain("发送于");
    expect(historyMessage.text()).not.toContain("模型");
  });

  it("读取、打开并二次确认删除当前账户的历史会话", async () => {
    mockApi.listChatSessions.mockResolvedValue([{ id: "session-1", chapterId: "stack", title: "栈的练习", updatedAt: "2026-08-12T00:00:00Z", messageCount: 2 }]);
    mockApi.getChatSession.mockResolvedValue({
      id: "session-1",
      chapterId: "stack",
      title: "栈的练习",
      updatedAt: "2026-08-12T00:00:00Z",
      messages: [{ id: 1, role: "assistant", content: "历史答案", sources: [], createdAt: "2026-08-12T00:00:00Z" }],
    });
    mockApi.deleteChatSession.mockResolvedValue(undefined);
    const wrapper = await mountView();
    await flushPromises();

    await wrapper.get('[data-testid="chat-session"]').trigger("click");
    await flushPromises();

    await wrapper.get('[data-testid="chat-request-delete"]').trigger("click");
    await wrapper.get('[data-testid="chat-confirm-delete"]').trigger("click");
    await flushPromises();

    expect(mockApi.deleteChatSession).toHaveBeenCalledWith("session-1");
    expect(wrapper.text()).toContain("暂未保存课程问答会话。");
    expect(document.activeElement).toBe(wrapper.get('[data-testid="chat-new-conversation"]').element);
  });
});
