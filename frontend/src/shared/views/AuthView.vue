<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ApiClientError } from "../api";
import { classifyAuthError } from "../auth";
import { auth } from "../../app/providers/runtime";

const props = defineProps<{ mode: "login" | "register" | "reset" }>();
const router = useRouter();
const route = useRoute();
const email = ref("");
const password = ref("");
const code = ref("");
const pending = ref(false);
const codePending = ref(false);
const cooldownSeconds = ref(0);
const feedback = ref("");
const error = ref("");
const isAdminEntry = computed(() => {
  const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "";
  const isAdminRedirect = /^\/admin(?:\/|$)/.test(redirect);
  const isAdminHost = typeof window !== "undefined" && /^admin\.structify\.cn$/i.test(window.location.hostname);
  return isAdminRedirect || isAdminHost;
});
const title = computed(() => {
  if (props.mode === "login") return isAdminEntry.value ? "管理端登录" : "登录数据结构工作台";
  return props.mode === "register" ? "创建学习账号" : "重置密码";
});
const emailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value.trim()));
const loginIdentifier = computed(() => email.value.trim());
const canRequestCode = computed(() => props.mode !== "login" && emailValid.value && !codePending.value && cooldownSeconds.value === 0);
let cooldownTimer: number | undefined;

function clearCooldown() {
  if (cooldownTimer !== undefined) {
    window.clearInterval(cooldownTimer);
    cooldownTimer = undefined;
  }
}

function startCooldown(seconds = 60) {
  clearCooldown();
  cooldownSeconds.value = seconds;
  cooldownTimer = window.setInterval(() => {
    cooldownSeconds.value = Math.max(0, cooldownSeconds.value - 1);
    if (cooldownSeconds.value === 0) clearCooldown();
  }, 1_000);
}

function userFacingError(cause: unknown): string {
  const code = (cause as { code?: string } | null)?.code;
  if (code === "VERIFICATION_CODE_EXPIRED" || code === "AUTH_CODE_INVALID") return "验证码无效或已过期，请重新发送。";
  const classification = classifyAuthError(cause);
  switch (classification.kind) {
    case "unauthorized": return "身份凭证无效或已过期，请重新登录或确认验证码。";
    case "forbidden": return "当前会话没有执行此操作的权限。";
    case "not-found": return "认证服务接口不存在，请稍后重试。";
    case "rate-limited": return `请求过于频繁，请在 ${classification.retryAfterSeconds ?? 60} 秒后重试。`;
    case "unavailable": return "认证服务暂时不可用，请稍后重试。";
    case "offline": return "网络不可用，已保留当前会话，请检查连接后重试。";
    case "timeout": return "请求超时，请重试。";
    case "server": return "服务器暂时无法处理请求，请重试。";
    default: return cause instanceof ApiClientError ? cause.message : "请求未完成，请稍后重试。";
  }
}

async function sendVerificationCode() {
  if (!canRequestCode.value) return;
  codePending.value = true;
  error.value = "";
  feedback.value = "";
  try {
    const delivery = await auth.requestCode({
      email: email.value.trim(),
      purpose: props.mode === "register" ? "register" : "reset",
    });
    feedback.value = delivery.message;
    startCooldown();
  } catch (cause) {
    error.value = userFacingError(cause);
  } finally {
    codePending.value = false;
  }
}

async function submit() {
  pending.value = true;
  feedback.value = "";
  error.value = "";
  try {
    if (props.mode === "login") {
      const identity = loginIdentifier.value;
      await auth.login(emailValid.value ? { email: identity, password: password.value } : { username: identity, password: password.value });
    }
    if (props.mode === "register") await auth.register({ email: email.value, code: code.value, password: password.value });
    if (props.mode === "reset") await auth.resetPassword({ email: email.value, code: code.value, password: password.value });
    await router.replace(typeof route.query.redirect === "string" ? route.query.redirect : "/");
  } catch (cause) {
    error.value = userFacingError(cause);
  } finally {
    pending.value = false;
  }
}

onBeforeUnmount(clearCooldown);
</script>

<template>
  <section class="auth-screen" :class="{ 'auth-screen--admin': isAdminEntry }" aria-labelledby="auth-title">
    <div class="auth-screen__intro">
      <h1 id="auth-title">{{ title }}</h1>
      <p v-if="!isAdminEntry">会话由 Spring v1 服务端管理，浏览器仅保留当前页面所需状态。</p>
    </div>
    <form class="auth-form" @submit.prevent="submit">
      <label>
        {{ props.mode === "login" ? "邮箱或用户名" : "邮箱" }}
        <input
          v-model="email"
          :type="props.mode === 'login' ? 'text' : 'email'"
          :autocomplete="props.mode === 'login' ? 'username' : 'email'"
          required
        />
      </label>
      <label v-if="props.mode !== 'login'" class="auth-code-field">
        <span>验证码</span>
        <div class="auth-code-field__controls">
          <input v-model="code" inputmode="numeric" autocomplete="one-time-code" required />
          <button
            class="button button--secondary auth-code-field__send"
            data-testid="send-verification-code"
            type="button"
            :disabled="!canRequestCode"
            @click="sendVerificationCode"
          >
            <span v-if="codePending">发送中...</span>
            <span v-else-if="cooldownSeconds > 0" data-testid="verification-countdown">重新发送 ({{ cooldownSeconds }})</span>
            <span v-else>发送验证码</span>
          </button>
        </div>
      </label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" minlength="8" required /></label>
      <p v-if="error" class="form-feedback form-feedback--error" role="alert">{{ error }}</p>
      <p v-if="feedback" class="form-feedback" role="status">{{ feedback }}</p>
      <button class="button button--primary" type="submit" :disabled="pending || codePending"><span>{{ pending ? "处理中..." : "继续" }}</span></button>
    </form>
    <nav class="auth-links" aria-label="账户操作">
      <RouterLink v-if="props.mode !== 'login'" to="/login">返回登录</RouterLink>
      <RouterLink v-else to="/register">创建账号</RouterLink>
      <RouterLink v-if="props.mode === 'login'" to="/reset-password">忘记密码</RouterLink>
    </nav>
  </section>
</template>

<style scoped>
.auth-code-field__controls { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; align-items: end; }
.auth-code-field__send { min-height: 44px; white-space: nowrap; }

/* The management sign-in is a separate route layout, so keep its material local. */
.auth-screen--admin {
  --auth-ink: #17262b;
  --auth-muted: #617278;
  --auth-line: rgba(23, 38, 43, 0.16);
  --auth-accent: #0f766e;
  --auth-accent-deep: #0a5d57;
  --auth-ease: cubic-bezier(0.23, 1, 0.32, 1);
  display: grid;
  width: min(100%, 456px);
  min-height: min(100dvh, 620px);
  max-width: none;
  align-content: center;
  gap: 22px;
  margin: 0 auto;
  padding: clamp(28px, 7vh, 76px) 0;
  color: var(--auth-ink);
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
  font-variant-numeric: tabular-nums;
}

.auth-screen--admin .auth-screen__intro { padding: 0 4px; }
.auth-screen--admin h1 {
  color: var(--auth-ink);
  font-size: clamp(32px, 4vw, 42px);
  font-weight: 720;
  line-height: 1.15;
}

.auth-screen--admin .auth-form {
  display: grid;
  gap: 18px;
  margin: 0;
  padding: clamp(20px, 3vw, 28px);
  border: 1px solid var(--auth-line);
  border-radius: 8px;
  background:
    radial-gradient(110% 130% at var(--liquid-x, 50%) var(--liquid-y, 50%), rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0) 55%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.86), rgba(242, 249, 247, 0.66));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.98),
    inset 1px 0 0 rgba(72, 166, 207, 0.1),
    inset -1px 0 0 rgba(212, 109, 146, 0.08),
    0 14px 30px rgba(38, 61, 58, 0.08);
  -webkit-backdrop-filter: blur(16px) saturate(1.08);
  backdrop-filter: blur(16px) saturate(1.08);
}

.auth-screen--admin .auth-form label {
  gap: 8px;
  color: var(--auth-muted);
  font-size: 13px;
  font-weight: 650;
}

.auth-screen--admin .auth-form input {
  min-height: 46px;
  padding: 0 13px;
  border-color: var(--auth-line);
  border-radius: 6px;
  background:
    radial-gradient(120% 140% at var(--liquid-x, 50%) var(--liquid-y, 50%), rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0) 58%),
    rgba(255, 255, 255, 0.64);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.94),
    inset 0 0 0 0.6px rgba(65, 159, 200, 0.1),
    inset -0.6px 0 0 rgba(213, 103, 141, 0.08);
  color: var(--auth-ink);
  transition: border-color 140ms ease, box-shadow 140ms ease, transform 140ms var(--auth-ease);
}

.auth-screen--admin .auth-form input:focus {
  border-color: rgba(15, 118, 110, 0.58);
  box-shadow:
    0 0 0 3px rgba(15, 118, 110, 0.16),
    inset 0 1px 0 rgba(255, 255, 255, 0.94),
    inset 0.7px 0 0 rgba(65, 159, 200, 0.16),
    inset -0.7px 0 0 rgba(213, 103, 141, 0.14);
  transform: translateY(-1px);
}

.auth-screen--admin .button {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  min-height: 46px;
  border-color: var(--auth-line);
  border-top-color: rgba(255, 255, 255, 0.82);
  border-right-color: rgba(214, 103, 145, 0.42);
  border-bottom-color: rgba(78, 177, 142, 0.38);
  border-left-color: rgba(49, 189, 213, 0.5);
  border-radius: 6px;
  background:
    radial-gradient(110% 150% at var(--liquid-x, 50%) var(--liquid-y, 50%), rgba(255, 255, 255, 0.62), rgba(255, 255, 255, 0) 58%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.82), rgba(239, 248, 246, 0.64));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.96),
    inset 0 -1px 0 rgba(23, 38, 43, 0.08),
    0 4px 10px rgba(38, 61, 58, 0.08);
  color: var(--auth-ink);
  font-weight: 700;
  transition: transform 120ms var(--auth-ease), border-color 140ms ease, box-shadow 140ms ease, background 140ms ease;
}

.auth-screen--admin .button::before,
.auth-screen--admin .button::after {
  position: absolute;
  z-index: 0;
  content: "";
  pointer-events: none;
}
.auth-screen--admin .button > span { position: relative; z-index: 1; }

.auth-screen--admin .button::before {
  inset: 1px;
  border: 1px solid rgba(255, 255, 255, 0.42);
  border-radius: inherit;
  background: radial-gradient(54% 120% at var(--liquid-x, 50%) var(--liquid-y, 50%), rgba(255, 255, 255, 0.4), rgba(255, 255, 255, 0) 66%);
  opacity: 0.5;
}

.auth-screen--admin .button::after {
  inset: 1px;
  border: 0;
  border-radius: inherit;
  box-shadow:
    inset 1.1px 0 0 rgba(56, 197, 224, 0.62),
    inset -1.1px 0 0 rgba(232, 105, 157, 0.56),
    inset 0 0.8px 0 rgba(255, 213, 125, 0.38),
    inset 0 -0.8px 0 rgba(69, 183, 143, 0.36);
  opacity: 0.74;
  transition: opacity 120ms ease, transform 120ms var(--auth-ease);
}

.auth-screen--admin .button--primary {
  border-color: rgba(7, 83, 78, 0.58);
  border-top-color: rgba(214, 255, 250, 0.74);
  border-right-color: rgba(255, 140, 190, 0.76);
  border-bottom-color: rgba(3, 69, 64, 0.94);
  border-left-color: rgba(73, 218, 232, 0.88);
  background:
    radial-gradient(120% 150% at var(--liquid-x, 50%) var(--liquid-y, 50%), rgba(255, 255, 255, 0.2), rgba(255, 255, 255, 0) 55%),
    linear-gradient(135deg, #16877c, var(--auth-accent-deep));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.28),
    inset 0 -1px 0 rgba(2, 56, 52, 0.36),
    0 6px 14px rgba(15, 118, 110, 0.16);
  color: #ffffff;
}

@media (hover: hover) and (pointer: fine) {
  .auth-screen--admin .button:not(:disabled):hover {
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.98),
      inset 0 -1px 0 rgba(23, 38, 43, 0.1),
      0 6px 14px rgba(38, 61, 58, 0.1);
    transform: translateY(-1px);
  }
  .auth-screen--admin .button:not(:disabled):hover::after { opacity: 0.74; }
}

.auth-screen--admin .button:not(:disabled):active {
  transform: translateY(1px) scale(0.985);
}
.auth-screen--admin .button:not(:disabled):active::after {
  opacity: 0.82;
  transform: scaleX(1.012) scaleY(1.025);
}

.auth-screen--admin .auth-links {
  margin: 0;
  padding: 0 4px;
  color: var(--auth-accent);
  font-size: 13px;
  font-weight: 650;
}
.auth-screen--admin .auth-links a { text-underline-offset: 3px; }
.auth-screen--admin .form-feedback { padding: 10px 12px; border-left: 3px solid currentColor; background: rgba(255, 255, 255, 0.52); }

@media (prefers-reduced-motion: reduce) {
  .auth-screen--admin *,
  .auth-screen--admin *::before,
  .auth-screen--admin *::after { transition: none; }
  .auth-screen--admin .auth-form input:focus,
  .auth-screen--admin .button:not(:disabled):active,
  .auth-screen--admin .button:not(:disabled):active::after { transform: none; }
}

@media (prefers-reduced-transparency: reduce) {
  .auth-screen--admin .auth-form,
  .auth-screen--admin .auth-form input,
  .auth-screen--admin .button { background: #ffffff; -webkit-backdrop-filter: none; backdrop-filter: none; }
  .auth-screen--admin .button--primary { background: var(--auth-accent); }
  .auth-screen--admin .button::before,
  .auth-screen--admin .button::after { display: none; }
}

@media (max-width: 420px) {
  .auth-screen--admin { width: min(100%, calc(100% - 28px)); min-height: min(100dvh, 560px); padding: 24px 0; }
  .auth-screen--admin .auth-form { padding: 18px; }
  .auth-code-field__controls { grid-template-columns: 1fr; }
  .auth-code-field__send { width: 100%; }
}
</style>
