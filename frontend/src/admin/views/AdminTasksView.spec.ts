import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import AdminTasksView from "./AdminTasksView.vue";

type TaskStatus = "PENDING" | "RUNNING" | "FAILED";

function makeTask(id: number, status: TaskStatus) {
  return {
    id,
    taskType: "STALE_TASK_RECOVERY",
    status,
    createdAt: "2026-08-12T01:00:00Z",
    startedAt: status === "PENDING" ? null : "2026-08-12T01:01:00Z",
    deadlineAt: "2026-08-12T01:11:00Z",
    heartbeatAt: status === "RUNNING" ? "2026-08-12T01:02:00Z" : null,
    finishedAt: status === "FAILED" ? "2026-08-12T01:03:00Z" : null,
    failureCode: status === "FAILED" ? "TASK_TIMEOUT" : null,
    failureReason: null,
    resultCount: null,
    retryCount: status === "FAILED" ? 1 : 0,
    maxAttempts: 3,
    cancelRequestedAt: null,
    requestedByUserId: 23,
    requestId: `req-task-${id}`,
  };
}

const pendingTask = makeTask(11, "PENDING");
const runningTask = makeTask(12, "RUNNING");
const failedTask = makeTask(13, "FAILED");

const tasks = vi.hoisted(() => vi.fn());
const task = vi.hoisted(() => vi.fn());
const recoverTimeouts = vi.hoisted(() => vi.fn());
const retryTask = vi.hoisted(() => vi.fn());
const cancelTask = vi.hoisted(() => vi.fn());

vi.mock("../api", () => ({
  adminApi: { tasks, task, recoverTimeouts, retryTask, cancelTask },
  adminErrorMessage: (failure: unknown, action: string) => {
    const error = failure as { code?: string; requestId?: string };
    return `${action}未完成（${error.code || "NETWORK_ERROR"}）。请求 ID：${error.requestId || "无"}`;
  },
  formatDate: (value?: string | null) => value || "未记录",
}));

function error(status: number, code: string, requestId: string) {
  return { status, code, requestId };
}

describe("AdminTasksView", () => {
  beforeEach(() => {
    tasks.mockReset().mockResolvedValue({ items: [pendingTask, runningTask, failedTask], page: 0, size: 20, total: 3 });
    task.mockReset().mockResolvedValue({ ...pendingTask, failureReason: "来自详情接口的任务说明" });
    recoverTimeouts.mockReset();
    retryTask.mockReset();
    cancelTask.mockReset();
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  it("loads all task types by default instead of pre-filtering to timeout recovery", async () => {
    const wrapper = mount(AdminTasksView);
    await flushPromises();

    expect(tasks).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, taskType: "" }));
    wrapper.unmount();
  });

  it("loads the real task detail through the public detail action", async () => {
    const wrapper = mount(AdminTasksView);
    await flushPromises();

    const firstDetail = wrapper.findAll("button").find((button) => button.text() === "详情");
    expect(firstDetail).toBeDefined();
    await firstDetail!.trigger("click");
    await flushPromises();

    expect(task).toHaveBeenCalledWith(11);
    expect(wrapper.text()).toContain("来自详情接口的任务说明");
  });

  it("offers cancel only for PENDING tasks, never RUNNING tasks", async () => {
    const wrapper = mount(AdminTasksView);
    await flushPromises();

    const rows = wrapper.findAll("tbody tr");
    expect(rows[0].text()).toContain("PENDING");
    expect(rows[0].findAll("button").some((button) => button.text() === "取消")).toBe(true);
    expect(rows[1].text()).toContain("RUNNING");
    expect(rows[1].findAll("button").some((button) => button.text() === "取消")).toBe(false);
  });

  it("shows the controlled 404 error when fresh task detail no longer exists", async () => {
    task.mockRejectedValue(error(404, "ADMIN_BACKGROUND_TASK_NOT_FOUND", "req-task-404"));
    const wrapper = mount(AdminTasksView);
    await flushPromises();

    const firstDetail = wrapper.findAll("button").find((button) => button.text() === "详情");
    await firstDetail!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("ADMIN_BACKGROUND_TASK_NOT_FOUND");
    expect(wrapper.text()).toContain("req-task-404");
  });

  it.each([
    [404, "ADMIN_BACKGROUND_TASK_NOT_FOUND", "req-task-404"],
    [409, "ADMIN_BACKGROUND_TASK_CONFLICT", "req-task-409"],
    [503, "BACKGROUND_TASK_SERVICE_UNAVAILABLE", "req-task-503"],
  ])("keeps a rejected PENDING cancel visible with backend %i evidence", async (status, code, requestId) => {
    cancelTask.mockRejectedValue(error(status, code, requestId));
    const wrapper = mount(AdminTasksView);
    await flushPromises();

    const pendingRow = wrapper.findAll("tbody tr")[0];
    const cancelButton = pendingRow.findAll("button").find((button) => button.text() === "取消");
    await cancelButton!.trigger("click");
    await flushPromises();

    expect(cancelTask).toHaveBeenCalledWith(11);
    expect(wrapper.text()).toContain(code);
    expect(wrapper.text()).toContain(requestId);
  });

  it("keeps a retry service outage visible with the backend 503 evidence", async () => {
    retryTask.mockRejectedValue(error(503, "BACKGROUND_TASK_SERVICE_UNAVAILABLE", "req-task-503"));
    const wrapper = mount(AdminTasksView);
    await flushPromises();

    const failedRow = wrapper.findAll("tbody tr")[2];
    const retryButton = failedRow.findAll("button").find((button) => button.text() === "重试");
    await retryButton!.trigger("click");
    await flushPromises();

    expect(retryTask).toHaveBeenCalledWith(13);
    expect(wrapper.text()).toContain("BACKGROUND_TASK_SERVICE_UNAVAILABLE");
    expect(wrapper.text()).toContain("req-task-503");
  });

  it("refreshes the active task list after a successful retry", async () => {
    tasks.mockReset()
      .mockResolvedValueOnce({ items: [failedTask], page: 0, size: 20, total: 1 })
      .mockResolvedValueOnce({ items: [], page: 0, size: 20, total: 0 });
    retryTask.mockResolvedValue({ ...failedTask, status: "PENDING", retryCount: 2 });
    const wrapper = mount(AdminTasksView);
    await flushPromises();

    const retryButton = wrapper.findAll("button").find((button) => button.text() === "重试");
    await retryButton!.trigger("click");
    await flushPromises();

    expect(tasks).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("暂无后台任务");
  });

  it("keeps a successful timeout recovery visible when the follow-up list refresh fails", async () => {
    tasks.mockReset()
      .mockResolvedValueOnce({ items: [pendingTask], page: 0, size: 20, total: 1 })
      .mockRejectedValueOnce(error(503, "BACKGROUND_TASK_SERVICE_UNAVAILABLE", "req-task-refresh-503"));
    recoverTimeouts.mockResolvedValue({ ...pendingTask, id: 99 });
    const wrapper = mount(AdminTasksView);
    await flushPromises();

    const recoverButton = wrapper.findAll("button").find((button) => button.text().includes("恢复超时任务"));
    if (!recoverButton) throw new Error("Missing recovery button");
    await recoverButton.trigger("click");
    await flushPromises();

    expect(recoverTimeouts).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("超时恢复任务 #99 已提交，但任务列表刷新失败");
    expect(wrapper.text()).not.toContain("后台任务读取失败");
  });
});
