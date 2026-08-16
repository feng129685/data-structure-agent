import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { defineComponent } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { ApiClientError } from "../../shared/api";
import AdminSettingsView from "./AdminSettingsView.vue";

const getModelConfig = vi.hoisted(() => vi.fn());
const updateModelConfig = vi.hoisted(() => vi.fn());
const testModelConnection = vi.hoisted(() => vi.fn());

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return { ...actual, adminApi: { getModelConfig, updateModelConfig, testModelConnection } };
});

const storedConfig = {
  provider: "stored-provider",
  baseUrl: "https://provider.example/v1",
  model: "stored-model",
  apiKeyConfigured: true,
  temperature: 0.4,
  maxOutputTokens: 2048,
  requestTimeoutMs: 30000,
  retryCount: 1,
  dailyTokenQuota: 50000,
  enabled: true,
  updatedAt: "2026-08-12T01:00:00Z",
};

function field(wrapper: VueWrapper, label: string) {
  const match = wrapper.findAll("label").find((candidate) => candidate.text().includes(label));
  if (!match) throw new Error(`Missing field: ${label}`);
  return match.get("input");
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/admin/settings", component: AdminSettingsView },
      { path: "/admin", component: { template: "<p>管理总览</p>" } },
    ],
  });
  await router.push("/admin/settings");
  await router.isReady();
  const wrapper = mount(defineComponent({ template: "<RouterView />" }), { global: { plugins: [router] } });
  await flushPromises();
  return { router, wrapper };
}

function apiError(status: number, code: string, requestId: string) {
  return new ApiClientError({ status, code, requestId, message: "安全错误消息", details: [] });
}

describe("AdminSettingsView", () => {
  beforeEach(() => {
    getModelConfig.mockReset().mockResolvedValue({
      available: false,
      reason: "NOT_CONFIGURED",
      configuration: null,
    });
    updateModelConfig.mockReset();
    testModelConnection.mockReset();
    localStorage.clear();
  });

  afterEach(() => vi.restoreAllMocks());

  it("renders a real empty form when no configuration exists", async () => {
    const { wrapper } = await mountView();

    expect(wrapper.text()).toContain("尚未保存模型配置");
    expect(field(wrapper, "Provider").element.value).toBe("");
    expect(field(wrapper, "Model ID").element.value).toBe("");
    expect(field(wrapper, "Base URL").element.value).toBe("");
    expect(field(wrapper, "API Key").element.value).toBe("");
    expect(wrapper.text()).not.toContain("deepseek");
    wrapper.unmount();
  });

  it("prevents browser navigation when the public form contains unsaved changes", async () => {
    const { wrapper } = await mountView();

    await field(wrapper, "Provider").setValue("provider-from-admin");
    const event = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(true);
    wrapper.unmount();

    const eventAfterUnmount = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(eventAfterUnmount);
    expect(eventAfterUnmount.defaultPrevented).toBe(false);
  });

  it("does not block navigation when the initial read fails before any edit", async () => {
    getModelConfig.mockRejectedValue(apiError(503, "MODEL_CONFIG_UNAVAILABLE", "req-model-load-503"));
    const { wrapper } = await mountView();

    const event = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
    wrapper.unmount();
  });

  it("blocks a route change when the administrator declines to discard a dirty form", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const { router, wrapper } = await mountView();
    await field(wrapper, "Model ID").setValue("unsaved-model");

    await router.push("/admin");

    expect(confirm).toHaveBeenCalledWith("模型配置还有未保存的更改，确定离开此页面吗？");
    expect(router.currentRoute.value.fullPath).toBe("/admin/settings");
    wrapper.unmount();
  });

  it("never backfills or persists an API key and clears it after a successful save", async () => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: { ...storedConfig, apiKey: "server-secret-must-not-render" } });
    updateModelConfig.mockResolvedValue({ ...storedConfig, model: "next-model", updatedAt: "2026-08-12T02:00:00Z" });
    const storageWrite = vi.spyOn(Storage.prototype, "setItem");
    const { wrapper } = await mountView();

    const keyInput = field(wrapper, "API Key");
    expect(keyInput.attributes("type")).toBe("password");
    expect(keyInput.element.value).toBe("");
    await field(wrapper, "Model ID").setValue("next-model");
    await keyInput.setValue("browser-secret");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateModelConfig).toHaveBeenCalledWith(expect.objectContaining({ model: "next-model", apiKey: "browser-secret" }));
    expect(keyInput.element.value).toBe("");
    expect(storageWrite).not.toHaveBeenCalled();
    expect(wrapper.text()).not.toContain("browser-secret");
    expect(wrapper.text()).toContain("配置已保存");
    wrapper.unmount();
  });

  it("clears the API key after a failed save without exposing it in the error", async () => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    updateModelConfig.mockRejectedValue(apiError(409, "MODEL_CONFIG_CONFLICT", "req-model-409"));
    const { wrapper } = await mountView();
    const keyInput = field(wrapper, "API Key");
    await field(wrapper, "Provider").setValue("changed-provider");
    await keyInput.setValue("browser-secret-on-failure");

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(keyInput.element.value).toBe("");
    expect(wrapper.text()).toContain("MODEL_CONFIG_CONFLICT");
    expect(wrapper.text()).toContain("req-model-409");
    expect(wrapper.text()).not.toContain("browser-secret-on-failure");
    wrapper.unmount();
  });

  it.each([
    ["Provider", "changed-provider"],
    ["Base URL", "https://other-provider.example/v1"],
  ])("requires a fresh API key when %s changes", async (label, value) => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    const { wrapper } = await mountView();
    await field(wrapper, label).setValue(value);

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateModelConfig).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("API_KEY_REQUIRED");
    wrapper.unmount();
  });

  it("allows a model-only update to keep the stored credential", async () => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    updateModelConfig.mockResolvedValue({ ...storedConfig, model: "next-model" });
    const { wrapper } = await mountView();
    await field(wrapper, "Model ID").setValue("next-model");

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateModelConfig).toHaveBeenCalledWith(expect.objectContaining({ model: "next-model" }));
    expect(updateModelConfig.mock.calls[0][0]).not.toHaveProperty("apiKey");
    expect(wrapper.text()).not.toContain("API_KEY_REQUIRED");
    wrapper.unmount();
  });

  it.each([
    [400, "MODEL_CONFIG_INVALID", "req-model-400"],
    [409, "MODEL_CONFIG_CONFLICT", "req-model-409"],
    [503, "MODEL_CONFIG_UNAVAILABLE", "req-model-503"],
  ])("shows the safe backend error code and request id for HTTP %i", async (status, code, requestId) => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    updateModelConfig.mockRejectedValue(apiError(status, code, requestId));
    const { wrapper } = await mountView();
    await field(wrapper, "Model ID").setValue("next-model");

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain(code);
    expect(wrapper.text()).toContain(requestId);
    wrapper.unmount();
  });

  it("classifies a transport failure as a retryable network error", async () => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    updateModelConfig.mockRejectedValue(new TypeError("Failed to fetch"));
    const { wrapper } = await mountView();
    await field(wrapper, "Model ID").setValue("next-model");

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("NETWORK_ERROR");
    expect(wrapper.text()).toContain("重试");
    wrapper.unmount();
  });

  it("disables concurrent actions while a save request is in flight", async () => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    let finishSave!: (value: typeof storedConfig) => void;
    updateModelConfig.mockImplementation(() => new Promise((resolve) => { finishSave = resolve; }));
    const { wrapper } = await mountView();
    await field(wrapper, "Model ID").setValue("next-model");

    await wrapper.get("form").trigger("submit");
    await wrapper.vm.$nextTick();

    const buttons = wrapper.findAll("button");
    expect(buttons.find((button) => button.text().includes("保存中"))?.attributes()).toHaveProperty("disabled");
    expect(buttons.find((button) => button.text().includes("测试连接"))?.attributes()).toHaveProperty("disabled");
    await wrapper.get("form").trigger("submit");
    expect(updateModelConfig).toHaveBeenCalledTimes(1);
    finishSave({ ...storedConfig, model: "next-model" });
    await flushPromises();
    wrapper.unmount();
  });

  it("requires saving browser edits before testing the stored server configuration", async () => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    testModelConnection.mockResolvedValue({ connected: true, code: "CONNECTED" });
    const { wrapper } = await mountView();
    await field(wrapper, "API Key").setValue("unsaved-browser-secret");

    const testButton = wrapper.findAll("button").find((button) => button.text().includes("测试连接"));
    if (!testButton) throw new Error("Missing connection test button");
    await testButton.trigger("click");
    await flushPromises();

    expect(testButton.attributes()).toHaveProperty("disabled");
    expect(testModelConnection).not.toHaveBeenCalled();
    expect(updateModelConfig).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("当前表单有未保存的更改");
    expect(wrapper.text()).not.toContain("unsaved-browser-secret");
    wrapper.unmount();
  });

  it("rejects out-of-range local generation controls before calling the API", async () => {
    getModelConfig.mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    const { wrapper } = await mountView();
    await field(wrapper, "重试次数").setValue("7");

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateModelConfig).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("VALIDATION_ERROR");
    expect(wrapper.text()).toContain("重试次数");
    wrapper.unmount();
  });
});
