import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import AdminMailConfigView from "./AdminMailConfigView.vue";

const getMailConfig = vi.hoisted(() => vi.fn());
const updateMailConfig = vi.hoisted(() => vi.fn());
const testMailConnection = vi.hoisted(() => vi.fn());
const sendTestMail = vi.hoisted(() => vi.fn());

vi.mock("../api", () => ({
  adminApi: { getMailConfig, updateMailConfig, testMailConnection, sendTestMail },
  adminErrorMessage: () => "操作未完成（NETWORK_ERROR）。请重试。",
}));

const storedConfig = {
  siteName: "Structify",
  enabled: true,
  smtpHost: "smtp.example.edu",
  smtpPort: 465,
  securityMode: "SSL",
  smtpUsername: "mailer@example.edu",
  smtpPasswordConfigured: true,
  fromEmail: "mailer@example.edu",
  fromName: "数据结构智能体",
  connectionTimeoutSeconds: 5,
  verificationTtlMinutes: 10,
  resendIntervalSeconds: 60,
  sessionTtlDays: 30,
  verificationSubject: "[{{site_name}}] 邮箱验证码",
  verificationTemplateHtml: "<main><h1>{{code}}</h1></main>",
};

function field(wrapper: VueWrapper, label: string) {
  const match = wrapper.findAll("label").find((candidate) => candidate.text().includes(label));
  if (!match) throw new Error(`Missing field: ${label}`);
  return match.get("input");
}

describe("AdminMailConfigView", () => {
  beforeEach(() => {
    getMailConfig.mockReset().mockResolvedValue({ available: true, reason: null, configuration: storedConfig });
    updateMailConfig.mockReset().mockResolvedValue(storedConfig);
    testMailConnection.mockReset().mockResolvedValue({ connected: true, code: "CONNECTED" });
    sendTestMail.mockReset().mockResolvedValue({ accepted: true, code: "TEST_EMAIL_SENT" });
    localStorage.clear();
  });

  it("keeps the SMTP password out of the loaded form and sends an explicit clear only when requested", async () => {
    const storageWrite = vi.spyOn(Storage.prototype, "setItem");
    const wrapper = mount(AdminMailConfigView);
    await flushPromises();

    const password = field(wrapper, "SMTP 密码");
    expect(password.attributes("type")).toBe("password");
    expect(password.element.value).toBe("");
    expect(password.attributes("placeholder")).toContain("留空表示保留");
    expect(wrapper.text()).not.toContain("server-secret-must-not-render");

    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(updateMailConfig).toHaveBeenLastCalledWith(expect.not.objectContaining({ password: expect.anything(), clearPassword: true }));

    const clearPassword = wrapper.get("input[name=clear-smtp-password]");
    await clearPassword.setValue(true);
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateMailConfig).toHaveBeenLastCalledWith(expect.objectContaining({ clearSmtpPassword: true }));
    expect(storageWrite).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("renders the verification template only inside a sandboxed iframe preview", async () => {
    const wrapper = mount(AdminMailConfigView);
    await flushPromises();

    const preview = wrapper.get("iframe[title='验证码邮件预览']");
    expect(preview.attributes("sandbox")).toBe("");
    expect(preview.attributes("srcdoc")).toContain("123456");
    expect(wrapper.findAll("main")).toHaveLength(0);
    wrapper.unmount();
  });

  it("does not test changed SMTP identity with a password that only belongs to the saved identity", async () => {
    const wrapper = mount(AdminMailConfigView);
    await flushPromises();

    await field(wrapper, "SMTP 主机").setValue("smtp.changed.example.edu");
    const testButton = wrapper.findAll("button").find((button) => button.text().includes("测试连接"));
    if (!testButton) throw new Error("Missing connection test button");
    await testButton.trigger("click");
    await flushPromises();

    expect(testMailConnection).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("连接身份已改变且没有输入密码");
    wrapper.unmount();
  });

  it("keeps a newly entered SMTP password after a connection test so save sends it", async () => {
    const wrapper = mount(AdminMailConfigView);
    await flushPromises();

    await wrapper.get("input[name=smtp-password]").setValue("test-only-smtp-password");
    await wrapper.get(".mail-card--delivery .mail-card__actions button:not([type=submit])").trigger("click");
    await flushPromises();

    expect(testMailConnection).toHaveBeenLastCalledWith(expect.objectContaining({ smtpPassword: "test-only-smtp-password" }));

    const beforeUnload = new Event("beforeunload", { cancelable: true });
    expect(window.dispatchEvent(beforeUnload)).toBe(false);
    expect(beforeUnload.defaultPrevented).toBe(true);

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateMailConfig).toHaveBeenLastCalledWith(expect.objectContaining({ smtpPassword: "test-only-smtp-password" }));
    expect((wrapper.get("input[name=smtp-password]").element as HTMLInputElement).value).toBe("");
    wrapper.unmount();
  });

  it("keeps a newly entered SMTP password after a test email so save sends it", async () => {
    const wrapper = mount(AdminMailConfigView);
    await flushPromises();

    await wrapper.get("input[name=smtp-password]").setValue("test-only-smtp-password");
    await wrapper.get(".mail-card--test input[type=email]").setValue("admin@example.test");
    await wrapper.get(".mail-card--test button").trigger("click");
    await flushPromises();

    expect(sendTestMail).toHaveBeenLastCalledWith(expect.objectContaining({ smtpPassword: "test-only-smtp-password" }), "admin@example.test");

    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateMailConfig).toHaveBeenLastCalledWith(expect.objectContaining({ smtpPassword: "test-only-smtp-password" }));
    wrapper.unmount();
  });

  it("keeps an SMTP password pending after a failed save", async () => {
    updateMailConfig.mockRejectedValueOnce(new Error("test save failure"));
    const wrapper = mount(AdminMailConfigView);
    await flushPromises();

    await wrapper.get("input[name=smtp-password]").setValue("test-only-smtp-password");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect((wrapper.get("input[name=smtp-password]").element as HTMLInputElement).value).toBe("test-only-smtp-password");
    const beforeUnload = new Event("beforeunload", { cancelable: true });
    expect(window.dispatchEvent(beforeUnload)).toBe(false);
    expect(beforeUnload.defaultPrevented).toBe(true);
    wrapper.unmount();
  });
});
