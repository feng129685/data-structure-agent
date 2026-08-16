<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { auth } from "../providers/runtime";

const route = useRoute();
const router = useRouter();
const isAdmin = computed(() => route.meta.layout === "admin");
const mobileNavOpen = ref(false);
const mobileMenuToggle = ref<HTMLButtonElement | null>(null);
const mobileNavClose = ref<HTMLButtonElement | null>(null);
const mobileNavLayer = ref<HTMLElement | null>(null);
let mobileNavScrollLock: { bodyOverflow: string; rootOverflow: string } | null = null;

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
      { to: "/admin/mail", label: "邮件设置" },
    ]
  : [
      { to: "/user/chapters", label: "章节" },
      { to: "/user/coach", label: "教练" },
      { to: "/user/classroom", label: "课堂" },
      { to: "/user/animation", label: "舞台" },
    ]);

watch(() => route.fullPath, () => {
  closeMobileNavWithoutRestoringFocus();
});

function toggleMobileNav() {
  if (mobileNavOpen.value) {
    closeMobileNav();
    return;
  }

  lockMobileNavBackground();
  mobileNavOpen.value = true;
  void nextTick(() => mobileNavClose.value?.focus());
}

function closeMobileNav() {
  closeMobileNavWithFocus(true);
}

function closeMobileNavWithoutRestoringFocus() {
  closeMobileNavWithFocus(false);
}

function closeMobileNavWithFocus(restoreFocus: boolean) {
  if (!mobileNavOpen.value) return;
  mobileNavOpen.value = false;
  unlockMobileNavBackground();
  if (restoreFocus) void nextTick(() => mobileMenuToggle.value?.focus());
}

function lockMobileNavBackground() {
  if (mobileNavScrollLock) return;
  mobileNavScrollLock = {
    bodyOverflow: document.body.style.overflow,
    rootOverflow: document.documentElement.style.overflow,
  };
  document.body.style.overflow = "hidden";
  document.documentElement.style.overflow = "hidden";
}

function unlockMobileNavBackground() {
  if (!mobileNavScrollLock) return;
  document.body.style.overflow = mobileNavScrollLock.bodyOverflow;
  document.documentElement.style.overflow = mobileNavScrollLock.rootOverflow;
  mobileNavScrollLock = null;
}

function getMobileNavFocusableElements() {
  return Array.from(mobileNavLayer.value?.querySelectorAll<HTMLElement>([
    "a[href]",
    "button:not([disabled])",
    "input:not([disabled]):not([type='hidden'])",
    "select:not([disabled])",
    "textarea:not([disabled])",
    "[tabindex]:not([tabindex='-1'])",
  ].join(", ")) ?? []).filter((element) => element.tabIndex >= 0 && element.getAttribute("aria-hidden") !== "true");
}

function constrainMobileNavFocus(event: KeyboardEvent) {
  const focusableElements = getMobileNavFocusableElements();
  const mobileNav = mobileNavLayer.value;
  if (!mobileNav || focusableElements.length === 0) return;

  const firstElement = focusableElements[0];
  const lastElement = focusableElements[focusableElements.length - 1];
  const activeElement = document.activeElement;
  const focusIsInsideMenu = mobileNav.contains(activeElement);

  if (event.shiftKey && (!focusIsInsideMenu || activeElement === firstElement)) {
    event.preventDefault();
    lastElement.focus();
  } else if (!event.shiftKey && (!focusIsInsideMenu || activeElement === lastElement)) {
    event.preventDefault();
    firstElement.focus();
  }
}

function handleGlobalKeydown(event: KeyboardEvent) {
  if (!mobileNavOpen.value) return;
  if (event.key === "Escape") {
    event.preventDefault();
    closeMobileNav();
  } else if (event.key === "Tab") {
    constrainMobileNavFocus(event);
  }
}

function closeMobileNavAtDesktop() {
  if (window.innerWidth > 920) closeMobileNavWithoutRestoringFocus();
}

async function signOut() {
  closeMobileNavWithoutRestoringFocus();
  await auth.logout();
  await router.replace("/login");
}

onMounted(() => {
  window.addEventListener("resize", closeMobileNavAtDesktop);
  window.addEventListener("keydown", handleGlobalKeydown);
});
onBeforeUnmount(() => {
  unlockMobileNavBackground();
  window.removeEventListener("resize", closeMobileNavAtDesktop);
  window.removeEventListener("keydown", handleGlobalKeydown);
});
</script>

<template>
  <div class="app-frame" :class="{ 'app-frame--admin': isAdmin }">
    <header class="app-header" :class="{ 'app-header--admin': isAdmin }">
      <RouterLink class="app-brand" :to="isAdmin ? '/admin' : '/'" :aria-label="isAdmin ? '返回管理总览' : '返回首页'">
        <span class="app-brand__mark" aria-hidden="true">ds</span>
        <span class="app-brand__name">数据结构工作台</span>
      </RouterLink>

      <nav v-if="!isAdmin" class="app-nav" aria-label="学习端导航">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
      </nav>

      <span class="app-header__spacer"></span>
      <div class="app-user">
        <span class="app-user__email">{{ auth.state.user?.email || "访客" }}</span>
        <button v-if="hasRetainedSession" class="button button--small" type="button" @click="signOut">退出</button>
        <RouterLink v-else class="button button--small" to="/login">登录</RouterLink>
      </div>
      <button
        v-if="isAdmin"
        ref="mobileMenuToggle"
        class="admin-menu-toggle"
        type="button"
        :aria-expanded="mobileNavOpen"
        aria-controls="admin-mobile-navigation"
        :aria-label="mobileNavOpen ? '关闭管理端导航' : '打开管理端导航'"
        @click="toggleMobileNav"
      >
        <span></span><span></span><span></span>
      </button>
    </header>

    <Transition name="admin-menu">
      <div v-if="isAdmin && mobileNavOpen" id="admin-mobile-navigation" ref="mobileNavLayer" class="admin-mobile-nav-layer" role="dialog" aria-modal="true" aria-label="管理端导航" tabindex="-1">
        <div class="admin-mobile-nav-layer__topline">
          <span>管理导航</span>
          <button ref="mobileNavClose" class="admin-mobile-nav-layer__close" type="button" aria-label="关闭管理端导航" @click="closeMobileNav">关闭</button>
        </div>
        <nav class="admin-mobile-nav" aria-label="管理端移动导航">
          <RouterLink v-for="item in navItems" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
        </nav>
      </div>
    </Transition>

    <div v-if="isAdmin" class="admin-workspace">
      <aside class="admin-sidebar" data-layout="admin-sidebar">
        <nav class="admin-sidebar__nav" aria-label="管理端导航">
          <RouterLink v-for="item in navItems" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
        </nav>
      </aside>
      <main class="app-main admin-workspace__main" id="main-content"><slot /></main>
    </div>
    <main v-else class="app-main" id="main-content"><slot /></main>
    <footer v-if="!isAdmin" class="app-footer">Spring v1 共享基础 · 学习端</footer>
  </div>
</template>

<style scoped>
.app-frame--admin {
  --admin-ink: #f5f7f6;
  --admin-paper: #17262b;
  --admin-paper-muted: #627177;
  --admin-line: rgba(23, 38, 43, 0.12);
  --admin-line-strong: rgba(23, 38, 43, 0.26);
  --admin-panel: rgba(255, 255, 255, 0.78);
  --admin-panel-solid: #ffffff;
  --admin-accent: #0f766e;
  --admin-success: #18794e;
  --admin-warning: #a05c12;
  --admin-danger: #bb3f48;
  --admin-shadow: 0 12px 28px rgba(38, 61, 58, 0.08);
  min-height: 100vh;
  background: var(--admin-ink);
  color: var(--admin-paper);
  color-scheme: light;
}

/* The page stylesheet defines its own variables; bring its surfaces into the shell's light mode. */
.app-frame--admin :deep(.admin-page) {
  --admin-ink: #f5f7f6;
  --admin-paper: #17262b;
  --admin-paper-muted: #627177;
  --admin-line: rgba(23, 38, 43, 0.12);
  --admin-line-strong: rgba(23, 38, 43, 0.26);
  --admin-panel: rgba(255, 255, 255, 0.78);
  --admin-panel-solid: #ffffff;
  --admin-accent: #0f766e;
  --admin-success: #18794e;
  --admin-warning: #a05c12;
  --admin-danger: #bb3f48;
  --admin-shadow: 0 12px 28px rgba(38, 61, 58, 0.08);
}

.app-frame--admin :deep(.admin-signal-grid > div),
.app-frame--admin :deep(.admin-inspector__summary > div),
.app-frame--admin :deep(.guardrail-grid > div) {
  background: rgba(255, 255, 255, 0.7);
}

.app-header--admin {
  position: sticky;
  z-index: 20;
  top: 10px;
  width: min(1440px, calc(100% - 32px));
  min-height: 58px;
  margin: 10px auto 0;
  padding: 8px 12px 8px 16px;
  border: 1px solid rgba(23, 38, 43, 0.14);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 8px 22px rgba(38, 61, 58, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.9);
  -webkit-backdrop-filter: blur(16px) saturate(1.05);
  backdrop-filter: blur(16px) saturate(1.05);
  color: var(--admin-paper);
}

.app-header--admin .app-brand { color: var(--admin-paper); }
.app-header--admin .app-brand__mark {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 9px;
  background: var(--admin-paper);
  color: #ffffff;
  font-size: 11px;
  font-weight: 800;
}
.app-header--admin .app-brand__name { white-space: nowrap; }
.app-header--admin .app-user { color: var(--admin-paper-muted); }
.app-header--admin .app-user .button {
  min-height: 36px;
  --liquid-fill-start: rgba(255, 255, 255, 0.82);
  --liquid-fill-end: rgba(242, 248, 247, 0.68);
  color: var(--admin-paper);
  transition: transform 140ms ease, border-color 140ms ease, background-color 140ms ease;
}
.app-header--admin .app-user .button:hover {
  --liquid-fill-start: #ffffff;
  --liquid-fill-end: rgba(235, 247, 244, 0.9);
  transform: translateY(-1px);
}
.app-header--admin .app-user .button:active { transform: translateY(0); }

.admin-workspace {
  display: grid;
  flex: 1;
  grid-template-columns: 220px minmax(0, 1fr);
  width: min(1440px, calc(100% - 32px));
  margin: 0 auto;
  padding: 28px 0 52px;
  gap: clamp(20px, 3vw, 40px);
}

.admin-sidebar {
  position: sticky;
  top: 88px;
  align-self: start;
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--admin-line);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.68);
  box-shadow: 0 8px 22px rgba(38, 61, 58, 0.05), inset 0 1px 0 rgba(255, 255, 255, 0.9);
  -webkit-backdrop-filter: blur(14px) saturate(1.04);
  backdrop-filter: blur(14px) saturate(1.04);
}

.admin-sidebar__label {
  margin: 0 4px;
  color: var(--admin-paper-muted);
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0;
}

.admin-sidebar__nav {
  display: grid;
  gap: 4px;
}

.admin-sidebar__nav a,
.admin-mobile-nav a {
  display: flex;
  align-items: center;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 9px;
  color: var(--admin-paper-muted);
  text-decoration: none;
  transition: color 140ms ease, background-color 140ms ease, border-color 140ms ease, transform 140ms ease;
}

.admin-sidebar__nav a:hover,
.admin-sidebar__nav a.router-link-exact-active,
.admin-mobile-nav a:hover,
.admin-mobile-nav a.router-link-exact-active {
  border-color: rgba(15, 118, 110, 0.18);
  background: rgba(15, 118, 110, 0.1);
  color: var(--admin-paper);
}

.admin-sidebar__nav a:hover,
.admin-mobile-nav a:hover { transform: translateX(2px); }
.admin-sidebar__nav a:focus-visible,
.admin-mobile-nav a:focus-visible { outline: none; box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.2); }

.admin-workspace__main {
  width: 100%;
  min-width: 0;
  padding: 0 0 20px;
  animation: admin-panel-in 260ms ease-out both;
}

.admin-menu-toggle,
.admin-mobile-nav-layer { display: none; }

@keyframes admin-panel-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 920px) {
  .app-header--admin {
    top: 8px;
    width: calc(100% - 24px);
    min-height: 54px;
    margin-top: 8px;
    padding-left: 12px;
  }
  .app-header--admin .app-brand__name,
  .app-header--admin .app-user__email { display: none; }
  .app-header--admin .app-user { margin-left: auto; }
  .app-header--admin .app-user .button { min-height: 38px; }
  .admin-menu-toggle {
    display: inline-grid;
    place-content: center;
    gap: 4px;
    width: 40px;
    height: 40px;
    margin-left: 2px;
    padding: 0;
    border-color: transparent;
    border-radius: 10px;
    --liquid-fill-start: rgba(255, 255, 255, 0.8);
    --liquid-fill-end: rgba(241, 248, 246, 0.66);
    color: var(--admin-paper);
    cursor: pointer;
    transition: transform 140ms ease, background-color 140ms ease, border-color 140ms ease;
  }
  .admin-menu-toggle:hover { --liquid-fill-start: #ffffff; --liquid-fill-end: rgba(235, 247, 244, 0.9); }
  .admin-menu-toggle:active { transform: scale(0.97); }
  .admin-menu-toggle span {
    display: block;
    width: 16px;
    height: 1px;
    background: currentColor;
    transition: transform 140ms ease, opacity 140ms ease;
  }
  .admin-workspace {
    display: block;
    width: calc(100% - 24px);
    padding: 22px 0 36px;
  }
  .admin-sidebar { display: none; }
  .admin-workspace__main { padding-bottom: 16px; }
  .admin-mobile-nav-layer {
    position: fixed;
    z-index: 15;
    top: 74px;
    right: 12px;
    bottom: 12px;
    left: 12px;
    display: flex;
    flex-direction: column;
    padding: 16px;
    overflow: auto;
    border: 1px solid rgba(23, 38, 43, 0.14);
    border-radius: 14px;
    background: rgba(248, 250, 249, 0.94);
    box-shadow: 0 16px 36px rgba(38, 61, 58, 0.14);
    -webkit-backdrop-filter: blur(18px) saturate(1.04);
    backdrop-filter: blur(18px) saturate(1.04);
  }
  .admin-mobile-nav-layer__topline {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 12px;
    color: var(--admin-paper-muted);
    font-size: 12px;
    font-weight: 650;
  }
  .admin-mobile-nav-layer__close {
    min-height: 36px;
    padding: 0 12px;
    border-color: transparent;
    border-radius: 8px;
    --liquid-fill-start: rgba(255, 255, 255, 0.82);
    --liquid-fill-end: rgba(242, 248, 247, 0.68);
    color: var(--admin-paper);
    cursor: pointer;
    transition: transform 140ms ease, border-color 140ms ease, background-color 140ms ease;
  }
  .admin-mobile-nav-layer__close:hover { --liquid-fill-start: #ffffff; --liquid-fill-end: rgba(235, 247, 244, 0.9); }
  .admin-mobile-nav-layer__close:active { transform: scale(0.97); }
  .admin-mobile-nav { display: grid; gap: 6px; }
  .admin-mobile-nav a {
    min-height: 50px;
    padding: 0 14px;
    border-color: var(--admin-line);
    background: rgba(255, 255, 255, 0.52);
    font-size: 15px;
  }
  .admin-menu-enter-active,
  .admin-menu-leave-active { transition: opacity 240ms ease, transform 240ms ease; }
  .admin-menu-enter-from,
  .admin-menu-leave-to { opacity: 0; transform: translateY(-6px); }
}

@media (max-width: 520px) {
  .app-header--admin { width: calc(100% - 16px); }
  .admin-mobile-nav-layer { top: 70px; right: 8px; bottom: 8px; left: 8px; }
}

@media (prefers-reduced-motion: reduce) {
  .admin-sidebar__nav a,
  .admin-mobile-nav a,
  .app-header--admin .app-user .button,
  .admin-menu-toggle,
  .admin-mobile-nav-layer__close,
  .admin-menu-toggle span,
  .admin-menu-enter-active,
  .admin-menu-leave-active,
  .admin-workspace__main {
    animation: none !important;
    transition-duration: 1ms !important;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .app-header--admin,
  .admin-sidebar,
  .admin-mobile-nav-layer,
  .admin-mobile-nav a,
  .app-header--admin .app-user .button,
  .admin-menu-toggle,
  .admin-mobile-nav-layer__close { background: #ffffff; -webkit-backdrop-filter: none; backdrop-filter: none; }
}

@media (prefers-contrast: more) {
  .app-header--admin,
  .admin-sidebar,
  .admin-sidebar__nav a,
  .admin-mobile-nav a { border-color: var(--admin-paper); }
}
</style>
