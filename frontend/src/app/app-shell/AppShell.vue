<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { auth } from "../providers/runtime";

const route = useRoute();
const router = useRouter();
const isAdmin = computed(() => route.meta.layout === "admin");
// Transport failures keep the last verified user in the store; that is still a usable session.
const hasRetainedSession = computed(() => Boolean(auth.state.user));
const navItems = computed(() => isAdmin.value
  ? [
      { to: "/admin", label: "总览" },
      { to: "/admin/users", label: "用户" },
      { to: "/admin/reviews", label: "审核" },
      { to: "/admin/tasks", label: "后台任务" },
      { to: "/admin/audit", label: "审计" },
      { to: "/admin/settings", label: "模型设置" },
    ]
  : [{ to: "/user/chapters", label: "章节" }, { to: "/user/coach", label: "教练" }, { to: "/user/classroom", label: "课堂" }, { to: "/user/animation", label: "舞台" }]);

async function signOut() {
  await auth.logout();
  await router.replace("/login");
}
</script>

<template>
  <div class="app-frame" :class="{ 'app-frame--admin': isAdmin }">
    <header class="app-header">
      <RouterLink class="app-brand" to="/" aria-label="返回首页"><span class="app-brand__mark" aria-hidden="true">ds</span><span>数据结构工作台</span></RouterLink>
      <nav v-if="!isAdmin" class="app-nav" aria-label="学习端导航">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
      </nav>
      <span class="app-header__spacer"></span>
      <div class="app-user">
        <span class="app-user__email">{{ auth.state.user?.email || "访客" }}</span>
        <button v-if="hasRetainedSession" class="button button--small" type="button" @click="signOut">退出</button>
        <RouterLink v-else class="button button--small" to="/login">登录</RouterLink>
      </div>
    </header>
    <div v-if="isAdmin" class="admin-workspace">
      <aside class="admin-sidebar" data-layout="admin-sidebar">
        <p class="admin-sidebar__label">管理工作区</p>
        <nav class="admin-sidebar__nav" aria-label="管理端导航">
          <RouterLink v-for="item in navItems" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
        </nav>
      </aside>
      <main class="app-main admin-workspace__main" id="main-content"><slot /></main>
    </div>
    <main v-else class="app-main" id="main-content"><slot /></main>
    <footer class="app-footer">Spring v1 共享基础 · {{ isAdmin ? "管理端" : "学习端" }}</footer>
  </div>
</template>

<style scoped>
.admin-workspace {
  display: grid;
  flex: 1;
  grid-template-columns: 224px minmax(0, 1fr);
  width: min(1440px, 100%);
  margin: 0 auto;
}

.admin-sidebar {
  position: sticky;
  top: 64px;
  align-self: start;
  height: calc(100vh - 64px);
  padding: 28px 16px;
  overflow-y: auto;
  border-right: 1px solid var(--line);
  background: var(--surface);
}

.admin-sidebar__label {
  margin: 0 10px 12px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
}

.admin-sidebar__nav {
  display: grid;
  gap: 4px;
}

.admin-sidebar__nav a {
  display: flex;
  align-items: center;
  min-height: 44px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  text-decoration: none;
  transition: color 140ms ease-out, background-color 140ms ease-out;
}

.admin-sidebar__nav a:hover,
.admin-sidebar__nav a.router-link-exact-active {
  color: var(--text);
  background: var(--surface-subtle);
}

.admin-sidebar__nav a:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.admin-workspace__main {
  width: 100%;
  min-width: 0;
  padding: 36px 32px 64px;
}

@media (max-width: 820px) {
  .admin-workspace {
    display: block;
  }

  .admin-sidebar {
    position: static;
    height: auto;
    padding: 8px 12px;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }

  .admin-sidebar__label {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
  }

  .admin-sidebar__nav {
    display: flex;
    gap: 4px;
    overflow-x: auto;
    overscroll-behavior-inline: contain;
    scrollbar-width: thin;
  }

  .admin-sidebar__nav a {
    flex: 0 0 auto;
    white-space: nowrap;
  }

  .admin-workspace__main {
    padding: 28px 12px 56px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .admin-sidebar__nav a {
    transition: none;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .admin-sidebar { background: var(--surface); }
}

@media (prefers-contrast: more) {
  .admin-sidebar {
    border-color: var(--text);
  }

  .admin-sidebar__nav a.router-link-exact-active {
    outline: 2px solid currentColor;
    outline-offset: -2px;
  }
}
</style>
