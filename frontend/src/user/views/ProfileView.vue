<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import { auth } from "../../app/providers/runtime";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";

const router = useRouter();
const roleLabels = { STUDENT: "学生", TEACHER: "教师", ADMIN: "管理员" } as const;
const roles = computed(() => auth.state.user?.roles.map((role) => roleLabels[role]).join("、") || "未确认");
const sessionLabel = computed(() => ({ authenticated: "有效", restoring: "恢复中", disabled: "已停用", forbidden: "无权限", offline: "离线保留", error: "验证失败", anonymous: "未登录", idle: "待恢复" }[auth.state.status] || auth.state.status));

async function signOut() {
  await auth.logout();
  await router.replace("/login");
}
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="profile-title">
      <header class="user-page__heading"><div><p class="user-page__eyebrow">当前账户</p><h1 id="profile-title">个人资料</h1><p class="user-page__intro">只展示当前会话返回的非敏感账户信息。</p></div></header>
      <UserState v-if="!auth.state.user" mode="permission" title="当前没有可用会话" message="请重新登录以恢复学习工作台。"><RouterLink class="user-action user-action--primary" to="/login">前往登录</RouterLink></UserState>
      <template v-else>
        <dl class="user-kv user-panel"><dt>邮箱</dt><dd>{{ auth.state.user.email }}</dd><dt>账号编号</dt><dd>{{ auth.state.user.id }}</dd><dt>角色</dt><dd>{{ roles }}</dd><dt>会话状态</dt><dd>{{ sessionLabel }}</dd></dl>
        <p v-if="auth.state.status === 'offline' || auth.state.status === 'error'" class="inline-notice inline-notice--warning">当前显示的是最近一次验证成功的账户信息，网络恢复后会重新确认会话。</p>
        <div class="user-page__actions"><button class="user-action user-action--danger" type="button" @click="signOut">退出登录</button></div>
        <section class="user-panel"><h2>资料修改</h2><p>冻结契约目前仅提供当前账户查询，没有个人资料编辑接口，因此本页不提供不可保存的编辑表单。</p></section>
      </template>
    </section>
    <template #rail><div class="user-rail-list"><strong>数据来源</strong><p>GET /users/me</p><strong>凭据保护</strong><p>不会显示令牌、Cookie、验证码或密码。</p></div></template>
  </UserFrame>
</template>
