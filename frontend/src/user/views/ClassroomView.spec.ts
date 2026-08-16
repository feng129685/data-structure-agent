import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { mockApi } = vi.hoisted(() => ({
  mockApi: {
    listClassroomScripts: vi.fn(),
    startClassroom: vi.fn(),
    getClassroomSession: vi.fn(),
    actInClassroom: vi.fn(),
  },
}));

vi.mock("../runtime", () => ({ userApi: mockApi }));

import ClassroomView from "./ClassroomView.vue";

const scripts = [{ id: "script-1", chapterId: "stack", title: "Stack seminar", versionLabel: "v1" }];
const waitingSession = {
  id: "session-1",
  userId: 7,
  scriptId: "script-1",
  state: "WAITING" as const,
  paused: false,
  summary: null,
  stage: { question: "What rule does a stack follow?" },
};
const discussedSession = {
  ...waitingSession,
  state: "DISCUSS" as const,
  stage: { discussion: "Correct." },
  answerEvaluation: { status: "CORRECT" as const, misconception: null, feedback: "Correct: LIFO." },
};

async function mountView(path = "/user/classroom?chapterId=stack") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/user/classroom", component: { template: "<div />" } },
      { path: "/user/chapters/:chapterId", component: { template: "<div />" } },
      { path: "/user/animation", component: { template: "<div />" } },
    ],
  });
  await router.push(path);
  await router.isReady();
  return mount(ClassroomView, {
    global: {
      plugins: [router],
      stubs: { UserFrame: { template: "<div><slot /><slot name=\"rail\" /></div>" } },
    },
  });
}

describe("ClassroomView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.listClassroomScripts.mockResolvedValue(scripts);
    mockApi.startClassroom.mockResolvedValue(waitingSession);
    mockApi.actInClassroom.mockResolvedValue(discussedSession);
  });

  it("loads a chapter script, starts its session, and submits the learner answer", async () => {
    const wrapper = await mountView();
    await flushPromises();

    expect(mockApi.listClassroomScripts).toHaveBeenCalledWith("stack");
    expect(wrapper.text()).toContain("Stack seminar");

    await wrapper.get('[data-testid="classroom-start-script-1"]').trigger("click");
    await flushPromises();
    expect(mockApi.startClassroom).toHaveBeenCalledWith("script-1");
    expect(wrapper.vm.$router.currentRoute.value.query.sessionId).toBe("session-1");
    expect(wrapper.get('[data-testid="classroom-animation"]').attributes("href")).toBe("/user/animation?chapterId=stack&from=classroom&sessionId=session-1");

    await wrapper.get('[data-testid="classroom-answer"]').setValue("LIFO");
    await wrapper.get('[data-testid="classroom-action-ANSWER"]').trigger("click");
    await flushPromises();

    expect(mockApi.actInClassroom).toHaveBeenCalledWith("session-1", { action: "ANSWER", content: "LIFO" });
    expect(wrapper.text()).toContain("Correct: LIFO.");
  });

  it("enters an existing session from the URL and restores its state", async () => {
    mockApi.getClassroomSession.mockResolvedValue(discussedSession);
    const wrapper = await mountView("/user/classroom?chapterId=stack&sessionId=session-1");
    await flushPromises();

    expect(mockApi.getClassroomSession).toHaveBeenCalledWith("session-1");
    expect(wrapper.text()).toContain("Correct: LIFO.");
    expect(wrapper.text()).toContain("讨论");
    expect(mockApi.startClassroom).not.toHaveBeenCalled();
  });

  it("shows a permission state instead of classroom scripts when URL session recovery is denied", async () => {
    mockApi.getClassroomSession.mockRejectedValue({ status: 403 });
    const wrapper = await mountView("/user/classroom?chapterId=stack&sessionId=session-1");
    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toContain("当前账号没有权限");
    expect(wrapper.text()).not.toContain("Stack seminar");
    expect(wrapper.find('[data-testid="classroom-start-script-1"]').exists()).toBe(false);
    expect(wrapper.find("button").exists()).toBe(false);
  });

  it("retries a failed URL session recovery without falling back to classroom scripts", async () => {
    mockApi.getClassroomSession
      .mockRejectedValueOnce({ status: 503 })
      .mockResolvedValueOnce(discussedSession);
    const wrapper = await mountView("/user/classroom?chapterId=stack&sessionId=session-1");
    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toContain("学习服务暂不可用");
    expect(wrapper.text()).not.toContain("Stack seminar");
    await wrapper.get("button").trigger("click");
    await flushPromises();

    expect(mockApi.getClassroomSession).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("Correct: LIFO.");
    expect(wrapper.find('[data-testid="classroom-start-script-1"]').exists()).toBe(false);
  });
});
