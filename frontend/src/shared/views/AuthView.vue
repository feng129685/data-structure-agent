<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ApiClientError } from "../api";
import { auth } from "../../app/providers/runtime";

const props = defineProps<{ mode: "login" | "register" | "reset" }>();
const router = useRouter();
const route = useRoute();
const email = ref("");
const password = ref("");
const code = ref("");
const pending = ref(false);
const feedback = ref("");
const error = ref("");
const title = computed(() => props.mode === "login" ? "登录数据结构工作台" : props.mode === "register" ? "创建学习账号" : "重置密码");

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
    error.value = cause instanceof ApiClientError ? cause.message : "请求未完成，请稍后重试";
  } finally {
    pending.value = false;
  }
}
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
      <label v-if="props.mode !== 'login'">验证码<input v-model="code" inputmode="numeric" autocomplete="one-time-code" required /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" minlength="8" required /></label>
      <p v-if="error" class="form-feedback form-feedback--error" role="alert">{{ error }}</p>
      <p v-if="feedback" class="form-feedback" role="status">{{ feedback }}</p>
      <button class="button button--primary" type="submit" :disabled="pending">{{ pending ? "处理中..." : "继续" }}</button>
    </form>
    <nav class="auth-links" aria-label="账户操作">
      <RouterLink v-if="props.mode !== 'login'" to="/login">返回登录</RouterLink>
      <RouterLink v-else to="/register">创建账号</RouterLink>
      <RouterLink v-if="props.mode === 'login'" to="/reset-password">忘记密码</RouterLink>
    </nav>
  </section>
</template>
