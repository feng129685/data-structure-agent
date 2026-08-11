import type { RouteRecordRaw } from "vue-router";
import ModulePlaceholderView from "../shared/views/ModulePlaceholderView.vue";

const adminMeta = { requiresAuth: true, roles: ["ADMIN"], layout: "admin" } as const;

export const adminRoutes: RouteRecordRaw[] = [
  { path: "/admin", name: "admin-home", component: ModulePlaceholderView, meta: { ...adminMeta, module: "管理总览" } },
  { path: "/admin/users", name: "admin-users", component: ModulePlaceholderView, meta: { ...adminMeta, module: "用户管理", requiresCapability: "users" } },
  { path: "/admin/reviews", name: "admin-reviews", component: ModulePlaceholderView, meta: { ...adminMeta, module: "审核队列", requiresCapability: "reviewQueue" } },
  { path: "/admin/audit", name: "admin-audit", component: ModulePlaceholderView, meta: { ...adminMeta, module: "审计事件", requiresCapability: "audit" } },
  { path: "/admin/settings", name: "admin-settings", component: ModulePlaceholderView, meta: { ...adminMeta, module: "模型设置", requiresCapability: "modelSettings" } },
];
