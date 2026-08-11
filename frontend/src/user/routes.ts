import type { RouteRecordRaw } from "vue-router";
import ModulePlaceholderView from "../shared/views/ModulePlaceholderView.vue";

export const userRoutes: RouteRecordRaw[] = [
  { path: "/user", redirect: "/user/chapters", meta: { requiresAuth: true, layout: "shell", module: "学习端" } },
  { path: "/user/chapters", name: "user-chapters", component: ModulePlaceholderView, meta: { requiresAuth: true, layout: "shell", module: "章节与资源" } },
  { path: "/user/coach", name: "user-coach", component: ModulePlaceholderView, meta: { requiresAuth: true, layout: "shell", module: "学习教练" } },
  { path: "/user/classroom", name: "user-classroom", component: ModulePlaceholderView, meta: { requiresAuth: true, layout: "shell", module: "课堂" } },
  { path: "/user/animation", name: "user-animation", component: ModulePlaceholderView, meta: { requiresAuth: true, layout: "shell", module: "算法舞台" } },
  { path: "/user/profile", name: "user-profile", component: ModulePlaceholderView, meta: { requiresAuth: true, layout: "shell", module: "个人资料" } },
];
