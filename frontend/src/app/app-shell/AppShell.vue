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
        <span v-if="isAdmin" class="app-brand__mark app-brand__mark--admin" aria-hidden="true"></span>
        <span v-else class="app-brand__mark" aria-hidden="true">ds</span>
        <span class="app-brand__name">{{ isAdmin ? "管理后台" : "数据结构工作台" }}</span>
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
  --admin-ink: #14272b;
  --admin-canvas: #eef3f4;
  --admin-paper-muted: #647477;
  --admin-line: rgba(20, 39, 43, 0.13);
  --admin-line-strong: rgba(20, 39, 43, 0.24);
  --admin-accent: #08766f;
  --admin-focus: #176f96;
  --admin-success: #147a50;
  --admin-warning: #9a5b15;
  --admin-danger: #b53c47;
  min-height: 100vh;
  background: var(--admin-canvas);
  color: var(--admin-ink);
  color-scheme: light;
}

.app-header--admin {
  position: sticky;
  z-index: 70;
  top: 0;
  width: 100%;
  min-height: 64px;
  margin: 0;
  padding: 0 clamp(20px, 3vw, 48px);
  border: 0;
  border-bottom: 1px solid var(--admin-line);
  border-radius: 0;
  background: rgba(253, 254, 254, 0.9);
  box-shadow: inset 0 -1px 0 rgba(255, 255, 255, 0.72);
  -webkit-backdrop-filter: blur(14px) saturate(1.12);
  backdrop-filter: blur(14px) saturate(1.12);
  color: var(--admin-ink);
}

.app-header--admin .app-brand { color: var(--admin-ink); }
.app-header--admin .app-brand__mark--admin {
  position: relative;
  width: 28px;
  height: 28px;
  overflow: hidden;
  border: 1px solid rgba(6, 89, 83, 0.72);
  border-radius: 7px;
  background: #0b6963;
  box-shadow:
    inset 1px 0 0 rgba(95, 224, 244, 0.82),
    inset -1px 0 0 rgba(245, 145, 181, 0.58),
    inset 0 1px 0 rgba(235, 255, 252, 0.64),
    inset 0 -1px 0 rgba(5, 72, 67, 0.72);
}
.app-header--admin .app-brand__mark--admin::before,
.app-header--admin .app-brand__mark--admin::after {
  position: absolute;
  display: block;
  content: "";
}
.app-header--admin .app-brand__mark--admin::before {
  top: 6px;
  left: 6px;
  width: 7px;
  height: 7px;
  border: 1px solid rgba(255, 255, 255, 0.78);
  border-radius: 2px;
}
.app-header--admin .app-brand__mark--admin::after {
  right: 6px;
  bottom: 6px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
}
.app-header--admin .app-brand__name {
  color: var(--admin-ink);
  font-size: 15px;
  font-weight: 730;
  white-space: nowrap;
}
.app-header--admin .app-user { color: var(--admin-paper-muted); }

.admin-workspace {
  display: grid;
  flex: 1;
  grid-template-columns: 232px minmax(0, 1fr);
  width: min(1520px, calc(100% - 48px));
  margin: 0 auto;
  gap: clamp(28px, 4vw, 56px);
}

.admin-sidebar {
  position: sticky;
  top: 64px;
  align-self: start;
  min-height: calc(100vh - 64px);
  padding: 26px 18px 32px 0;
  border: 0;
  border-right: 1px solid var(--admin-line);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.admin-sidebar__nav,
.admin-mobile-nav {
  display: grid;
  gap: 3px;
}

.admin-sidebar__nav a,
.admin-mobile-nav a {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 42px;
  padding: 0 12px 0 14px;
  border: 1px solid transparent;
  border-radius: 7px;
  color: var(--admin-paper-muted);
  font-size: 14px;
  font-weight: 620;
  line-height: 1.35;
  text-decoration: none;
  transition: color 150ms ease, background-color 150ms ease, border-color 150ms ease, transform 150ms cubic-bezier(0.2, 0.72, 0.2, 1);
}

.admin-sidebar__nav a::before,
.admin-mobile-nav a::before {
  position: absolute;
  left: -1px;
  width: 2px;
  height: 18px;
  border-radius: 2px;
  background: var(--admin-accent);
  content: "";
  opacity: 0;
  transform: scaleY(0.55);
  transition: opacity 150ms ease, transform 150ms cubic-bezier(0.2, 0.72, 0.2, 1);
}

.admin-sidebar__nav a:hover,
.admin-mobile-nav a:hover {
  border-color: rgba(8, 118, 111, 0.14);
  background: rgba(255, 255, 255, 0.58);
  color: var(--admin-ink);
}

.admin-sidebar__nav a.router-link-exact-active,
.admin-mobile-nav a.router-link-exact-active {
  border-color: rgba(8, 118, 111, 0.15);
  background: rgba(8, 118, 111, 0.1);
  color: #075c56;
}

.admin-sidebar__nav a.router-link-exact-active::before,
.admin-mobile-nav a.router-link-exact-active::before {
  opacity: 1;
  transform: scaleY(1);
}

.admin-sidebar__nav a:focus-visible,
.admin-mobile-nav a:focus-visible {
  outline: 0;
  box-shadow: 0 0 0 3px rgba(23, 111, 150, 0.22);
}

.admin-workspace__main {
  width: 100%;
  min-width: 0;
  padding: 30px 0 56px;
  animation: admin-workspace-enter 220ms cubic-bezier(0.16, 0.82, 0.27, 1) both;
}

.admin-menu-toggle,
.admin-mobile-nav-layer { display: none; }

@keyframes admin-workspace-enter {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 920px) {
  .app-header--admin {
    min-height: 58px;
    padding: 0 14px;
  }
  .app-header--admin .app-brand__name,
  .app-header--admin .app-user__email { display: none; }
  .app-header--admin .app-user { margin-left: auto; }
  .admin-menu-toggle {
    display: inline-grid;
    place-content: center;
    gap: 4px;
    width: 40px;
    height: 40px;
    margin-left: 2px;
    padding: 0;
    border: 1px solid rgba(20, 39, 43, 0.14);
    border-radius: 7px;
    background: rgba(255, 255, 255, 0.7);
    color: var(--admin-ink);
    cursor: pointer;
    box-shadow: inset 1px 0 0 rgba(95, 224, 244, 0.3), inset -1px 0 0 rgba(245, 145, 181, 0.22);
    transition: transform 120ms ease-out, background-color 150ms ease, border-color 150ms ease;
  }
  .admin-menu-toggle:active { transform: scale(0.97); }
  .admin-menu-toggle span {
    display: block;
    width: 16px;
    height: 1px;
    background: currentColor;
    transition: transform 160ms cubic-bezier(0.2, 0.72, 0.2, 1), opacity 120ms ease;
  }
  .admin-menu-toggle[aria-expanded="true"] span:nth-child(1) { transform: translateY(5px) rotate(45deg); }
  .admin-menu-toggle[aria-expanded="true"] span:nth-child(2) { opacity: 0; }
  .admin-menu-toggle[aria-expanded="true"] span:nth-child(3) { transform: translateY(-5px) rotate(-45deg); }
  .admin-workspace {
    display: block;
    width: min(100% - 24px, 760px);
  }
  .admin-sidebar { display: none; }
  .admin-workspace__main { padding: 22px 0 40px; }
  .admin-mobile-nav-layer {
    position: fixed;
    z-index: 65;
    top: 58px;
    right: 0;
    bottom: 0;
    left: 0;
    display: flex;
    flex-direction: column;
    padding: 22px 14px 32px;
    overflow: auto;
    border: 0;
    border-top: 1px solid var(--admin-line);
    border-radius: 0;
    background: rgba(246, 249, 249, 0.96);
    box-shadow: none;
    -webkit-backdrop-filter: blur(16px) saturate(1.08);
    backdrop-filter: blur(16px) saturate(1.08);
  }
  .admin-mobile-nav-layer__topline {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin: 0 4px 14px;
    color: var(--admin-paper-muted);
    font-size: 13px;
    font-weight: 650;
  }
  .admin-mobile-nav-layer__close {
    min-height: 36px;
    padding: 0 11px;
    border: 1px solid var(--admin-line);
    border-radius: 7px;
    background: rgba(255, 255, 255, 0.72);
    color: var(--admin-ink);
    cursor: pointer;
  }
  .admin-mobile-nav { gap: 5px; }
  .admin-mobile-nav a {
    min-height: 48px;
    padding-left: 15px;
    border-color: rgba(20, 39, 43, 0.1);
    background: rgba(255, 255, 255, 0.58);
    font-size: 15px;
  }
  .admin-menu-enter-active,
  .admin-menu-leave-active { transition: opacity 200ms ease-out, transform 200ms cubic-bezier(0.16, 0.82, 0.27, 1); }
  .admin-menu-enter-from,
  .admin-menu-leave-to { opacity: 0; transform: translateY(-8px); }
}

@media (max-width: 520px) {
  .admin-workspace { width: calc(100% - 20px); }
}

@media (prefers-reduced-motion: reduce) {
  .admin-sidebar__nav a,
  .admin-mobile-nav a,
  .admin-sidebar__nav a::before,
  .admin-mobile-nav a::before,
  .admin-menu-toggle,
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
  .admin-mobile-nav-layer,
  .admin-menu-toggle,
  .admin-mobile-nav a,
  .admin-mobile-nav-layer__close { background: #ffffff; -webkit-backdrop-filter: none; backdrop-filter: none; }
}

@media (prefers-contrast: more) {
  .app-header--admin,
  .admin-sidebar,
  .admin-sidebar__nav a,
  .admin-mobile-nav a { border-color: var(--admin-ink); }
  .admin-sidebar { border-right-color: var(--admin-ink); }
}
</style>
