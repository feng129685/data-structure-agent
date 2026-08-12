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
const title = computed(() => props.mode === "login" ? "登录数据结构工作台" : props.mode === "register" ? "创建学习账号" : "重置密码");
const emailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value.trim()));
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
    if (props.mode === "login") await auth.login({ email: email.value, password: password.value });
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
  <section class="auth-screen" aria-labelledby="auth-title">
    <div class="auth-screen__intro">
      <p class="home-screen__kicker">STRUCTIFY / ACCESS</p>
      <h1 id="auth-title">{{ title }}</h1>
      <p>会话由 Spring v1 服务端管理，浏览器仅保留当前页面所需状态。</p>
    </div>
    <form class="auth-form" @submit.prevent="submit">
      <label>邮箱<input v-model="email" type="email" autocomplete="email" required /></label>
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
      <button class="button button--primary" type="submit" :disabled="pending || codePending">{{ pending ? "处理中..." : "继续" }}</button>
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
@media (max-width: 420px) {
  .auth-code-field__controls { grid-template-columns: 1fr; }
  .auth-code-field__send { width: 100%; }
}
</style>
