import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import AuthView from "./AuthView.vue";

const authMock = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  resetPassword: vi.fn(),
  requestCode: vi.fn(),
}));

vi.mock("../../app/providers/runtime", () => ({ auth: authMock }));

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div>home</div>" } },
      { path: "/login", component: { template: "<div>login</div>" } },
      { path: "/register", component: AuthView, props: { mode: "register" } },
      { path: "/reset-password", component: AuthView, props: { mode: "reset" } },
    ],
  });
}

async function mountAuth(mode: "register" | "reset") {
  const router = createTestRouter();
  await router.push(mode === "register" ? "/register" : "/reset-password");
  await router.isReady();
  return mount(AuthView, { props: { mode }, global: { plugins: [router] } });
}

describe("verification-code auth flow", () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it("validates email, sends the real request once, and starts a resend countdown", async () => {
    vi.useFakeTimers();
    let resolveDelivery!: (value: { message: string }) => void;
    authMock.requestCode.mockImplementationOnce(() => new Promise((resolve) => { resolveDelivery = resolve; }));
    const wrapper = await mountAuth("register");

    const send = () => wrapper.get('[data-testid="send-verification-code"]');
    expect(send().attributes("disabled")).toBeDefined();

    await wrapper.get('input[type="email"]').setValue("student@example.com");
    expect(send().attributes("disabled")).toBeUndefined();
    await send().trigger("click");
    await send().trigger("click");

    expect(authMock.requestCode).toHaveBeenCalledTimes(1);
    expect(authMock.requestCode).toHaveBeenCalledWith({ email: "student@example.com", purpose: "register" });
    expect(send().attributes("disabled")).toBeDefined();

    resolveDelivery({ message: "Verification code has been sent" });
    await flushPromises();

    expect(wrapper.get('[role="status"]').text()).toContain("Verification code has been sent");
    expect(wrapper.get('[data-testid="verification-countdown"]').text()).toContain("60");
    await vi.advanceTimersByTimeAsync(1_000);
    expect(wrapper.get('[data-testid="verification-countdown"]').text()).toContain("59");
  });

  it("keeps the form in place and gives a retry interval for a rate-limited code request", async () => {
    const rateLimitError = Object.assign(new Error("Too many requests"), {
      status: 429,
      code: "RATE_LIMITED",
      headers: new Headers({ "Retry-After": "30" }),
    });
    authMock.requestCode.mockRejectedValueOnce(rateLimitError);
    const wrapper = await mountAuth("reset");

    await wrapper.get('input[type="email"]').setValue("student@example.com");
    await wrapper.get('[data-testid="send-verification-code"]').trigger("click");
    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toContain("30");
    expect(authMock.requestCode).toHaveBeenCalledWith({ email: "student@example.com", purpose: "reset" });
  });

  it("does not claim success when the server reports an invalid or expired verification code", async () => {
    authMock.register.mockRejectedValueOnce(Object.assign(new Error("invalid code"), { status: 401, code: "AUTH_CODE_INVALID" }));
    const wrapper = await mountAuth("register");

    await wrapper.get('input[type="email"]').setValue("student@example.com");
    await wrapper.get('input[autocomplete="one-time-code"]').setValue("123456");
    await wrapper.get('input[type="password"]').setValue("correct-horse-battery-staple");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toContain("验证码无效或已过期");
    expect(authMock.register).toHaveBeenCalledWith({
      email: "student@example.com",
      code: "123456",
      password: "correct-horse-battery-staple",
    });
  });
});
