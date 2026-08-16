import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import UserState from "./UserState.vue";

describe("UserState", () => {
  it("将错误和权限拒绝作为紧急状态播报", () => {
    const error = mount(UserState, { props: { mode: "error", title: "加载失败", message: "请重试" } });
    const permission = mount(UserState, { props: { mode: "permission", title: "无权限", message: "请返回章节" } });

    expect(error.attributes("role")).toBe("alert");
    expect(permission.attributes("role")).toBe("alert");
  });

  it("将加载和空状态作为普通状态播报", () => {
    const loading = mount(UserState, { props: { mode: "loading", title: "加载中", message: "" } });
    const empty = mount(UserState, { props: { mode: "empty", title: "暂无内容", message: "" } });

    expect(loading.attributes("role")).toBe("status");
    expect(empty.attributes("role")).toBe("status");
  });
});
