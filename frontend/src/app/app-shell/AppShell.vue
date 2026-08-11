<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { auth } from "../providers/runtime";

const route = useRoute();
const router = useRouter();
const isAdmin = computed(() => route.meta.layout === "admin");
const navItems = computed(() => isAdmin.value
  ? [{ to: "/admin", label: "总览" }, { to: "/admin/users", label: "用户" }, { to: "/admin/reviews", label: "审核" }, { to: "/admin/audit", label: "审计" }]
  : [{ to: "/user/chapters", label: "章节" }, { to: "/user/coach", label: "教练" }, { to: "/user/classroom", label: "课堂" }, { to: "/user/animation", label: "舞台" }]);

async function signOut() {
  await auth.logout();
  await router.replace("/login");
}
</script>

<template>
  <div class="app-frame">
    <header class="app-header">
      <RouterLink class="app-brand" to="/" aria-label="返回首页"><span class="app-brand__mark" aria-hidden="true">ds</span><span>数据结构工作台</span></RouterLink>
      <nav class="app-nav" :aria-label="isAdmin ? '管理端导航' : '学习端导航'">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
      </nav>
      <span class="app-header__spacer"></span>
      <div class="app-user">
        <span class="app-user__email">{{ auth.state.user?.email || "访客" }}</span>
        <button v-if="auth.state.status === 'authenticated'" class="button button--small" type="button" @click="signOut">退出</button>
        <RouterLink v-else class="button button--small" to="/login">登录</RouterLink>
      </div>
    </header>
    <main class="app-main" id="main-content"><slot /></main>
    <footer class="app-footer">Spring v1 共享基础 · {{ isAdmin ? "管理端" : "学习端" }}</footer>
  </div>
</template>
