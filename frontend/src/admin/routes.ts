import type { RouteRecordRaw } from "vue-router";
import AdminHomeView from "./views/AdminHomeView.vue";
import AdminUsersView from "./views/AdminUsersView.vue";
import AdminReviewsView from "./views/AdminReviewsView.vue";
import AdminTasksView from "./views/AdminTasksView.vue";
import AdminAuditView from "./views/AdminAuditView.vue";
import AdminSettingsView from "./views/AdminSettingsView.vue";
import AdminMailConfigView from "./views/AdminMailConfigView.vue";

const adminMeta = { requiresAuth: true, roles: ["ADMIN"], layout: "admin" } as const;

export const adminRoutes: RouteRecordRaw[] = [
  { path: "/admin", name: "admin-home", component: AdminHomeView, meta: { ...adminMeta, module: "Admin overview" } },
  { path: "/admin/users", name: "admin-users", component: AdminUsersView, meta: { ...adminMeta, module: "Users and roles" } },
  { path: "/admin/reviews", name: "admin-reviews", component: AdminReviewsView, meta: { ...adminMeta, module: "Review queue" } },
  { path: "/admin/tasks", name: "admin-tasks", component: AdminTasksView, meta: { ...adminMeta, module: "Background tasks" } },
  { path: "/admin/audit", name: "admin-audit", component: AdminAuditView, meta: { ...adminMeta, module: "Audit events" } },
  { path: "/admin/settings", name: "admin-settings", component: AdminSettingsView, meta: { ...adminMeta, module: "Model settings" } },
  { path: "/admin/mail", name: "admin-mail", component: AdminMailConfigView, meta: { ...adminMeta, module: "Mail delivery" } },
];
