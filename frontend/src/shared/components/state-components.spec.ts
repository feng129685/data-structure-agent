import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import LoadingState from "./LoadingState.vue";
import ErrorState from "./ErrorState.vue";
import PermissionState from "./PermissionState.vue";
import RetryButton from "./RetryButton.vue";

describe("共享状态组件公共边界", () => {
  it("渲染中文状态、可访问名称和可触发重试", async () => {
    const retry = () => undefined;
    const wrapper = mount(ErrorState, { props: { title: "加载失败", message: "请稍后再试" } });
    expect(wrapper.text()).toContain("加载失败");
    expect(wrapper.get("[role=alert]").attributes("aria-live")).toBe("assertive");

    const retryWrapper = mount(RetryButton, { props: { onRetry: retry } });
    await retryWrapper.get("button").trigger("click");
    expect(retryWrapper.emitted("retry")).toBeTruthy();

    expect(mount(LoadingState, { props: { label: "正在恢复会话" } }).get("[role=status]").text()).toContain("正在恢复会话");
    expect(mount(PermissionState, { props: { title: "需要登录" } }).get("[role=alert]").text()).toContain("需要登录");
  });
});
