import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import LiquidMetalButton from "./LiquidMetalButton.vue";

describe("LiquidMetalButton", () => {
  it("透传原生按钮属性，支持文本和图标 slot，并转发点击", async () => {
    const wrapper = mount(LiquidMetalButton, {
      props: { type: "submit" },
      attrs: { "aria-label": "保存当前设置", name: "settings-action", value: "save" },
      slots: {
        default: "保存设置",
        icon: "<svg data-test-icon=\"true\" viewBox=\"0 0 1 1\" />",
      },
    });

    const button = wrapper.get("button");
    expect(button.attributes("type")).toBe("submit");
    expect(button.attributes("name")).toBe("settings-action");
    expect(button.attributes("value")).toBe("save");
    expect(button.attributes("aria-label")).toBe("保存当前设置");
    expect(wrapper.find("[data-test-icon=true]").exists()).toBe(true);
    expect(button.text()).toContain("保存设置");

    await button.trigger("click");
    expect(wrapper.emitted("click")).toHaveLength(1);
  });

  it("loading 时禁用提交并声明忙碌状态", async () => {
    const wrapper = mount(LiquidMetalButton, {
      props: { loading: true },
      slots: { default: "保存设置" },
    });

    const button = wrapper.get("button");
    expect(button.attributes("disabled")).toBeDefined();
    expect(button.attributes("aria-busy")).toBe("true");
    expect(wrapper.find(".liquid-metal-button__spinner").exists()).toBe(true);

    await button.trigger("click");
    expect(wrapper.emitted("click")).toBeUndefined();
  });
});
