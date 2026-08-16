<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import LiquidMetalButton from "../../admin/components/LiquidMetalButton.vue";
import { ApiClientError } from "../api";
import { classifyAuthError } from "../auth";
import { auth } from "../../app/providers/runtime";

const props = defineProps<{ mode: "login" | "register" | "reset" }>();

type AuthStep = "identity" | "credentials";

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
const authStep = ref<AuthStep>("identity");
const passwordVisible = ref(false);
const identityInput = ref<HTMLInputElement | null>(null);
const passwordInput = ref<HTMLInputElement | null>(null);
let cooldownTimer: number | undefined;

const isAdminEntry = computed(() => {
  const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "";
  const isAdminRedirect = /^\/admin(?:\/|$)/.test(redirect);
  const isAdminHost = typeof window !== "undefined" && /^admin\.structify\.cn$/i.test(window.location.hostname);
  return isAdminRedirect || isAdminHost;
});
const isLogin = computed(() => props.mode === "login");
const emailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value.trim()));
const identityValid = computed(() => isLogin.value ? email.value.trim().length > 0 : emailValid.value);
const credentialsReady = computed(() => {
  if (props.mode === "login") return password.value.trim().length > 0;
  return code.value.trim().length > 0 && password.value.trim().length > 0;
});
const loginIdentifier = computed(() => email.value.trim());
const canRequestCode = computed(() => props.mode !== "login" && emailValid.value && !codePending.value && cooldownSeconds.value === 0);
const passwordType = computed(() => passwordVisible.value ? "text" : "password");
const isCredentialsStep = computed(() => authStep.value === "credentials");
const identityLabel = computed(() => props.mode === "login" ? "邮箱或用户名" : "邮箱");
const identityPlaceholder = computed(() => props.mode === "login" ? "邮箱或用户名" : "邮箱地址");

const title = computed(() => {
  if (authStep.value === "identity") {
    if (props.mode === "login") return isAdminEntry.value ? "登录管理端" : "开始使用";
    return props.mode === "register" ? "创建账户" : "重置密码";
  }
  if (props.mode === "login") return "输入密码";
  return props.mode === "register" ? "验证账户" : "设置新密码";
});

const subtitle = computed(() => {
  if (authStep.value === "identity") {
    if (props.mode === "login") return isAdminEntry.value ? "使用管理员账户继续" : "使用 Structify 账户继续";
    return props.mode === "register" ? "先输入你的邮箱地址" : "先验证你的邮箱地址";
  }
  if (props.mode === "login") return "请输入账户密码";
  return "验证码和新密码将由服务器安全校验";
});

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
  const codeValue = (cause as { code?: string } | null)?.code;
  if (codeValue === "VERIFICATION_CODE_EXPIRED" || codeValue === "AUTH_CODE_INVALID") return "验证码无效或已过期，请重新发送。";
  const classification = classifyAuthError(cause);
  switch (classification.kind) {
    case "unauthorized": return "身份凭证无效或已过期，请重新登录或确认验证码。";
    case "forbidden": return "当前会话没有执行此操作的权限。";
    case "not-found": return "认证服务接口不存在，请稍后重试。";
    case "rate-limited": return `请求过于频繁，请在 ${classification.retryAfterSeconds ?? 60} 秒后重试。`;
    case "unavailable": return "认证服务暂时不可用，请稍后重试。";
    case "offline": return "网络不可用，请检查连接后重试。";
    case "timeout": return "请求超时，请重试。";
    case "server": return "服务器暂时无法处理请求，请重试。";
    default: return cause instanceof ApiClientError ? cause.message : "请求未完成，请稍后重试。";
  }
}

async function advanceFromIdentity() {
  if (!identityValid.value || pending.value) return;
  error.value = "";
  authStep.value = "credentials";
  await nextTick();
  passwordInput.value?.focus();
}

function handleIdentityKeydown(event: KeyboardEvent) {
  if (event.key !== "Enter") return;
  event.preventDefault();
  void advanceFromIdentity();
}

function goBack() {
  if (pending.value || authStep.value === "identity") return;
  authStep.value = "identity";
  error.value = "";
  void nextTick(() => identityInput.value?.focus());
}

async function sendVerificationCode() {
  if (!canRequestCode.value) return;
  codePending.value = true;
  feedback.value = "";
  error.value = "";
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
  if (pending.value || codePending.value) return;

  // Keep password managers and direct form submission working before the visual flow advances.
  if (authStep.value === "identity" && !credentialsReady.value) {
    await advanceFromIdentity();
    return;
  }
  if (!identityValid.value) {
    error.value = props.mode === "login" ? "请输入邮箱或用户名。" : "请输入有效的邮箱地址。";
    return;
  }
  if (!credentialsReady.value) {
    error.value = props.mode === "login" ? "请输入密码。" : "请输入验证码和密码。";
    return;
  }

  pending.value = true;
  feedback.value = "";
  error.value = "";
  try {
    if (props.mode === "login") {
      const identity = loginIdentifier.value;
      await auth.login(emailValid.value ? { email: identity, password: password.value } : { username: identity, password: password.value });
    }
    if (props.mode === "register") await auth.register({ email: email.value.trim(), code: code.value.trim(), password: password.value });
    if (props.mode === "reset") await auth.resetPassword({ email: email.value.trim(), code: code.value.trim(), password: password.value });
    await router.replace(typeof route.query.redirect === "string" ? route.query.redirect : "/");
  } catch (cause) {
    error.value = userFacingError(cause);
  } finally {
    pending.value = false;
  }
}

watch(() => props.mode, () => {
  authStep.value = "identity";
  passwordVisible.value = false;
  feedback.value = "";
  error.value = "";
});

onBeforeUnmount(clearCooldown);
</script>

<template>
  <main class="auth-stage auth-screen" :class="{ 'auth-stage--admin': isAdminEntry, 'auth-screen--admin': isAdminEntry }" aria-labelledby="auth-title">
    <div class="auth-stage__refraction" aria-hidden="true"></div>

    <header class="auth-brand">
      <RouterLink class="auth-brand__link" to="/" aria-label="返回 Structify">
        <span class="auth-brand__mark" aria-hidden="true">S</span>
        <span class="auth-brand__name">Structify</span>
      </RouterLink>
    </header>

    <section class="auth-flow" :class="{ 'auth-flow--credentials': isCredentialsStep }">
      <Transition name="auth-copy" mode="out-in">
        <div :key="`${props.mode}-${authStep}`" class="auth-flow__heading">
          <h1 id="auth-title">{{ title }}</h1>
          <p>{{ subtitle }}</p>
        </div>
      </Transition>

      <form class="auth-form" novalidate @submit.prevent="submit">
        <label class="auth-field" :class="{ 'auth-field--identity-compact': isCredentialsStep }">
          <span v-show="isCredentialsStep" class="auth-field__floating-label">{{ identityLabel }}</span>
          <span class="auth-field__symbol" aria-hidden="true">@</span>
          <input
            ref="identityInput"
            v-model="email"
            :type="props.mode === 'login' ? 'text' : 'email'"
            :autocomplete="props.mode === 'login' ? 'username' : 'email'"
            :placeholder="identityPlaceholder"
            :aria-label="identityLabel"
            :aria-invalid="Boolean(error && !identityValid)"
            required
            @keydown="handleIdentityKeydown"
          />
          <LiquidMetalButton
            v-show="authStep === 'identity' && identityValid"
            class="auth-step-action"
            variant="quiet"
            type="button"
            aria-label="继续填写密码"
            :disabled="pending"
            @click="advanceFromIdentity"
          ><span aria-hidden="true">&rarr;</span></LiquidMetalButton>
        </label>

        <Transition name="auth-fields">
          <div v-show="isCredentialsStep" class="auth-credentials">
            <label v-if="props.mode !== 'login'" class="auth-field auth-field--credential">
              <span class="auth-field__floating-label">邮箱验证码</span>
              <span class="auth-field__symbol auth-field__symbol--code" aria-hidden="true">#</span>
              <input
                v-model="code"
                inputmode="numeric"
                autocomplete="one-time-code"
                placeholder="输入验证码"
                aria-label="邮箱验证码"
                required
              />
              <button
                class="auth-code-action"
                data-testid="send-verification-code"
                type="button"
                :disabled="!canRequestCode"
                @click="sendVerificationCode"
              >
                <span v-if="codePending">发送中</span>
                <span v-else-if="cooldownSeconds > 0" data-testid="verification-countdown">{{ cooldownSeconds }}s</span>
                <span v-else>发送</span>
              </button>
            </label>

            <label class="auth-field auth-field--credential">
              <span class="auth-field__floating-label">密码</span>
              <button
                class="auth-field__symbol auth-field__symbol--toggle"
                type="button"
                :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
                @click="passwordVisible = !passwordVisible"
              >{{ passwordVisible ? "○" : "●" }}</button>
              <input
                ref="passwordInput"
                v-model="password"
                :type="passwordType"
                :autocomplete="props.mode === 'login' ? 'current-password' : 'new-password'"
                :placeholder="props.mode === 'login' ? '密码' : '至少 8 位密码'"
                aria-label="密码"
                minlength="8"
                required
              />
              <LiquidMetalButton
                v-show="credentialsReady"
                class="auth-step-action"
                variant="quiet"
                type="submit"
                :aria-label="props.mode === 'login' ? '登录' : props.mode === 'register' ? '创建账号' : '更新密码'"
                :loading="pending"
                :disabled="pending || codePending"
              ><span aria-hidden="true">&rarr;</span></LiquidMetalButton>
            </label>
          </div>
        </Transition>

        <p v-if="error" class="form-feedback form-feedback--error" role="alert">{{ error }}</p>
        <p v-if="feedback" class="form-feedback" role="status">{{ feedback }}</p>

        <button v-if="isCredentialsStep" class="auth-back" type="button" :disabled="pending" @click="goBack"><span aria-hidden="true">&larr;</span> 返回</button>
      </form>

      <nav class="auth-links" aria-label="账户操作">
        <RouterLink v-if="props.mode !== 'login'" to="/login">返回登录</RouterLink>
        <RouterLink v-else to="/register">创建账号</RouterLink>
        <RouterLink v-if="props.mode === 'login'" to="/reset-password">忘记密码</RouterLink>
      </nav>
    </section>
  </main>
</template>

<style scoped>
.auth-stage.auth-screen {
  --auth-ink: #202426;
  --auth-muted: #687074;
  --auth-accent: #1f6872;
  --auth-ease-out: cubic-bezier(0.22, 1, 0.36, 1);
  --auth-ease: cubic-bezier(0.25, 1, 0.5, 1);
  position: relative;
  display: grid;
  width: 100%;
  max-width: none;
  min-height: 100dvh;
  margin: 0;
  padding: 76px 20px 32px;
  overflow: hidden;
  place-items: center;
  isolation: isolate;
  background-color: #f3f3f1;
  background-image:
    linear-gradient(0deg, transparent 24%, rgba(57, 66, 68, 0.06) 25%, rgba(57, 66, 68, 0.06) 26%, transparent 27%, transparent 74%, rgba(57, 66, 68, 0.06) 75%, rgba(57, 66, 68, 0.06) 76%, transparent 77%, transparent),
    linear-gradient(90deg, transparent 24%, rgba(57, 66, 68, 0.06) 25%, rgba(57, 66, 68, 0.06) 26%, transparent 27%, transparent 74%, rgba(57, 66, 68, 0.06) 75%, rgba(57, 66, 68, 0.06) 76%, transparent 77%, transparent);
  background-size: 58px 58px;
  color: var(--auth-ink);
  color-scheme: light;
  font-family: var(--font-sans, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif);
}

.auth-stage__refraction,
.auth-stage__refraction::before,
.auth-stage__refraction::after { position: absolute; inset: 0; pointer-events: none; }

.auth-stage__refraction {
  z-index: -1;
  overflow: hidden;
  background:
    linear-gradient(126deg, transparent 16%, rgba(101, 209, 219, 0.22) 37%, transparent 55%),
    linear-gradient(306deg, transparent 23%, rgba(241, 148, 192, 0.18) 45%, transparent 66%);
  filter: blur(0.2px);
}

.auth-stage__refraction::before {
  top: auto;
  right: -18%;
  bottom: 6%;
  left: auto;
  width: 76%;
  height: 24%;
  background: linear-gradient(90deg, transparent, rgba(255, 219, 132, 0.26), rgba(102, 210, 234, 0.23), transparent);
  filter: blur(24px);
  transform: rotate(-12deg);
}

.auth-stage__refraction::after {
  top: 16%;
  right: auto;
  bottom: auto;
  left: -18%;
  width: 64%;
  height: 20%;
  background: linear-gradient(90deg, transparent, rgba(112, 222, 208, 0.2), rgba(248, 203, 126, 0.18), transparent);
  filter: blur(24px);
  transform: rotate(-13deg);
}

.auth-brand { position: absolute; top: 20px; left: 22px; z-index: 1; }

.auth-brand__link {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--auth-ink);
  font-size: 16px;
  font-weight: 750;
  line-height: 1;
  text-decoration: none;
}

.auth-brand__mark {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid rgba(22, 33, 35, 0.76);
  border-radius: 9px;
  background: #23292a;
  box-shadow: inset 1px 1px rgba(255, 255, 255, 0.16), 0 5px 12px rgba(32, 44, 44, 0.13);
  color: #f8fbfa;
  font-family: Georgia, "Times New Roman", serif;
  font-size: 18px;
  font-weight: 700;
}

.auth-brand__name { font-variant-numeric: lining-nums; }
.auth-flow { position: relative; z-index: 1; display: grid; width: min(100%, 320px); gap: 24px; align-content: center; justify-items: center; text-align: center; }
.auth-flow__heading { display: grid; gap: 9px; }

.auth-flow h1 {
  margin: 0;
  color: var(--auth-ink);
  font-family: Georgia, "Times New Roman", serif;
  font-size: clamp(40px, 5vw, 58px);
  font-weight: 400;
  letter-spacing: 0;
  line-height: 1.04;
}

.auth-flow__heading p { margin: 0; color: var(--auth-muted); font-size: 14px; font-weight: 620; line-height: 1.55; }
.auth-stage .auth-form { display: grid; width: 100%; gap: 18px; margin: 0; padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }

.auth-field {
  --field-angle: 220deg;
  position: relative;
  display: flex;
  width: 100%;
  min-height: 54px;
  align-items: center;
  gap: 8px;
  padding: 5px 6px 5px 9px;
  overflow: visible;
  border: 1px double rgba(47, 57, 58, 0.16);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.09);
  box-shadow: inset 2px -2px 1px -1px rgba(255, 255, 255, 0.9), inset -2px 2px 1px -1px rgba(255, 255, 255, 0.9), inset 6px -6px 1px -6px rgba(255, 255, 255, 0.55), inset -6px 6px 1px -6px rgba(255, 255, 255, 0.55), inset 0 0 2px rgba(0, 0, 0, 0.44), 0 7px 13px rgba(32, 43, 44, 0.12);
  -webkit-backdrop-filter: blur(7px) saturate(1.08);
  backdrop-filter: blur(7px) saturate(1.08);
  transition: transform 180ms var(--auth-ease), box-shadow 180ms ease, background-color 180ms ease;
}

.auth-field::before,
.auth-field::after { position: absolute; border-radius: inherit; content: ""; pointer-events: none; }

.auth-field::before {
  z-index: 2;
  inset: -1px;
  padding: 1.2px;
  background: conic-gradient(from var(--field-angle) at 50% 50%, rgba(95, 229, 246, 0.72), rgba(255, 255, 255, 0.74) 15%, transparent 29% 40%, rgba(247, 128, 206, 0.67) 53%, rgba(255, 222, 122, 0.67) 61%, transparent 74% 86%, rgba(110, 238, 203, 0.64));
  opacity: 0.68;
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  mask-composite: exclude;
  transition: opacity 180ms ease, filter 180ms ease;
}

.auth-field::after {
  z-index: 1;
  inset: 2px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.24);
  background: linear-gradient(118deg, transparent 0 35%, rgba(255, 255, 255, 0.48) 47%, transparent 59%);
  mix-blend-mode: screen;
  opacity: 0.74;
  transition: opacity 180ms ease, transform 180ms var(--auth-ease-out);
}

.auth-field:focus-within { background: rgba(255, 255, 255, 0.18); box-shadow: inset 2px -2px 1px -1px rgba(255, 255, 255, 0.94), inset -2px 2px 1px -1px rgba(255, 255, 255, 0.94), inset 0 0 2px rgba(0, 0, 0, 0.52), 0 10px 17px rgba(32, 43, 44, 0.16); transform: translateY(-1px); }
.auth-field:focus-within::before { filter: saturate(1.18) brightness(1.08); opacity: 0.96; }
.auth-field:focus-within::after { opacity: 1; transform: translateX(9%); }

.auth-stage .auth-field input { position: relative; z-index: 4; width: 0; height: 40px; min-height: 0; flex: 1 1 auto; min-width: 0; padding: 0; border: 0; border-radius: 0; outline: 0; background: transparent; box-shadow: none; color: var(--auth-ink); font-size: 14px; font-weight: 520; line-height: 1; }
.auth-stage .auth-field input:focus { border: 0; background: transparent; box-shadow: none; }
.auth-field input::placeholder { color: rgba(32, 36, 38, 0.55); }
.auth-field input:-webkit-autofill { -webkit-text-fill-color: var(--auth-ink); -webkit-box-shadow: 0 0 0 100px transparent inset; transition: background-color 9999s ease-out; }

.auth-field__symbol { position: relative; z-index: 4; display: grid; width: 34px; height: 34px; flex: 0 0 34px; place-items: center; padding: 0; border: 0; border-radius: 50%; background: transparent; color: rgba(32, 36, 38, 0.82); font-size: 16px; font-weight: 700; line-height: 1; }
.auth-field__symbol--code { color: #a86c2e; }
.auth-field__symbol--toggle { cursor: pointer; color: #29676f; font-size: 15px; }
.auth-field__floating-label { position: absolute; z-index: 6; top: -17px; left: 14px; color: var(--auth-muted); font-size: 11px; font-weight: 700; line-height: 1; }
.auth-field--identity-compact { margin-top: 10px; }
.auth-field--credential { min-height: 54px; }
.auth-credentials { display: grid; gap: 22px; }

.auth-step-action { position: relative; z-index: 5; width: 42px; min-width: 42px; height: 42px; min-height: 42px; padding: 0; font-size: 20px; }

.auth-code-action { position: relative; z-index: 5; min-width: 52px; min-height: 34px; padding: 0 11px; border: 1px solid rgba(31, 70, 76, 0.16); border-radius: 999px; background: rgba(255, 255, 255, 0.44); box-shadow: inset 0 1px rgba(255, 255, 255, 0.92), 0 3px 7px rgba(32, 54, 55, 0.08); color: var(--auth-accent); cursor: pointer; font: inherit; font-size: 12px; font-weight: 730; transition: transform 150ms var(--auth-ease), background-color 150ms ease, box-shadow 150ms ease; }
.auth-code-action:hover:not(:disabled) { background: rgba(255, 255, 255, 0.72); transform: scale(0.975); }
.auth-code-action:active:not(:disabled) { transform: scale(0.95); }
.auth-code-action:disabled { cursor: not-allowed; opacity: 0.48; }

.form-feedback { margin: 0; padding: 9px 11px; border: 1px solid rgba(31, 104, 114, 0.18); border-radius: 8px; background: rgba(255, 255, 255, 0.35); color: #22616a; font-size: 12px; line-height: 1.5; text-align: left; }
.form-feedback--error { border-color: rgba(170, 65, 88, 0.32); color: #96384e; }

.auth-back { justify-self: start; min-height: 30px; padding: 0; border: 0; background: transparent; color: rgba(32, 36, 38, 0.68); cursor: pointer; font: inherit; font-size: 13px; font-weight: 650; transition: color 150ms ease, transform 150ms var(--auth-ease); }
.auth-back:hover:not(:disabled) { color: var(--auth-ink); transform: translateX(-2px); }
.auth-back:disabled { cursor: not-allowed; opacity: 0.5; }

.auth-links { display: flex; width: 100%; justify-content: space-between; gap: 16px; padding-top: 4px; color: rgba(32, 36, 38, 0.72); font-size: 13px; font-weight: 650; text-align: left; }
.auth-links a { color: inherit; text-underline-offset: 4px; }
.auth-links a:hover { color: var(--auth-accent); }

.auth-copy-enter-active, .auth-copy-leave-active, .auth-fields-enter-active, .auth-fields-leave-active { transition: opacity 220ms var(--auth-ease-out), transform 220ms var(--auth-ease-out), filter 220ms ease; }
.auth-copy-enter-from, .auth-copy-leave-to { opacity: 0; filter: blur(3px); transform: translateY(7px) scale(0.985); }
.auth-fields-enter-from, .auth-fields-leave-to { opacity: 0; filter: blur(3px); transform: translateY(-6px) scale(0.99); }

@media (hover: hover) and (pointer: fine) { .auth-brand__link:hover .auth-brand__mark { transform: translateY(-1px); } }
@media (min-width: 640px) { .auth-brand { left: 50%; transform: translateX(-50%); } }
@media (max-width: 520px) { .auth-stage.auth-screen { padding-right: 16px; padding-left: 16px; } .auth-brand { top: 18px; left: 18px; } .auth-flow { width: min(100%, 340px); gap: 22px; } .auth-flow h1 { font-size: 42px; } }
@media (prefers-reduced-motion: reduce) { .auth-stage *, .auth-stage *::before, .auth-stage *::after { animation: none !important; transition-duration: 1ms !important; } }
@media (prefers-reduced-transparency: reduce) { .auth-stage__refraction { display: none; } .auth-field, .auth-code-action { background: rgba(255, 255, 255, 0.94); -webkit-backdrop-filter: none; backdrop-filter: none; } .auth-field::after { display: none; } }
</style>
