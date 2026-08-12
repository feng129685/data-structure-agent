import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClientError } from "../../shared/api";
import { flushPromises, mount } from "@vue/test-utils";
import AdminUsersView from "./AdminUsersView.vue";

const listUser = {
  id: 7,
  email: "admin@example.edu",
  status: "ACTIVE" as const,
  disabledReason: null,
  disabledAt: null,
  roles: ["ADMIN" as const],
  createdAt: "2026-08-10T01:00:00Z",
  updatedAt: "2026-08-10T02:00:00Z",
};

const detailUser = {
  ...listUser,
  updatedAt: "2026-08-12T09:45:00Z",
};

const users = vi.hoisted(() => vi.fn());
const user = vi.hoisted(() => vi.fn());
const updateUserStatus = vi.hoisted(() => vi.fn());
const updateUserRoles = vi.hoisted(() => vi.fn());

vi.mock("../api", () => ({
  adminApi: { users, user, updateUserStatus, updateUserRoles },
  adminErrorMessage: (failure: unknown, action: string) => {
    const error = failure as { code?: string; requestId?: string };
    return `${action}未完成（${error.code || "NETWORK_ERROR"}）。请求 ID：${error.requestId || "无"}`;
  },
  formatDate: (value?: string | null) => value || "未记录",
}));

describe("AdminUsersView", () => {
  beforeEach(() => {
    users.mockReset().mockResolvedValue({ items: [listUser], page: 0, size: 20, total: 1 });
    user.mockReset().mockResolvedValue(detailUser);
    updateUserStatus.mockReset();
    updateUserRoles.mockReset();
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  it("sends zero-based filters and keeps a protected 409 visible", async () => {
    updateUserStatus.mockRejectedValue(new ApiClientError({
      status: 409,
      code: "ADMIN_LAST_ADMIN_PROTECTED",
      message: "不能禁用最后一个管理员",
      requestId: "req-user-409",
      details: [],
    }));
    const wrapper = mount(AdminUsersView);
    await flushPromises();

    const search = wrapper.get("input[placeholder='按邮箱搜索']");
    await search.setValue("admin@example.edu");
    const selects = wrapper.findAll("form select");
    await selects[0].setValue("ACTIVE");
    await selects[1].setValue("ADMIN");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(users).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, search: "admin@example.edu", status: "ACTIVE", role: "ADMIN" }));

    const disable = wrapper.findAll("button").find((button) => button.text() === "禁用");
    await disable!.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("ADMIN_LAST_ADMIN_PROTECTED");
    expect(wrapper.text()).toContain("req-user-409");
  });

  it("loads the real user detail through the public view action", async () => {
    const wrapper = mount(AdminUsersView);
    await flushPromises();

    const viewButton = wrapper.findAll("button").find((button) => button.text() === "查看");
    expect(viewButton).toBeDefined();
    await viewButton!.trigger("click");
    await flushPromises();

    expect(user).toHaveBeenCalledWith(7);
    expect(wrapper.get("aside[aria-label='用户详情']").text()).toContain("2026-08-12T09:45:00Z");
  });
});
