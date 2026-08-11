import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import { auth } from "../app/providers/runtime";
import { createRouteGuard } from "./guards";
import HomeView from "../shared/views/HomeView.vue";
import AuthView from "../shared/views/AuthView.vue";
import ForbiddenView from "../shared/views/ForbiddenView.vue";
import NotFoundView from "../shared/views/NotFoundView.vue";
import { userRoutes } from "../user/routes";
import { adminRoutes } from "../admin/routes";

export const routes: RouteRecordRaw[] = [
  { path: "/", name: "home", component: HomeView, meta: { layout: "shell", module: "首页" } },
  { path: "/login", name: "login", component: AuthView, props: { mode: "login" }, meta: { layout: "auth" } },
  { path: "/register", name: "register", component: AuthView, props: { mode: "register" }, meta: { layout: "auth" } },
  { path: "/reset-password", name: "reset-password", component: AuthView, props: { mode: "reset" }, meta: { layout: "auth" } },
  ...userRoutes,
  ...adminRoutes,
  { path: "/403", name: "forbidden", component: ForbiddenView, meta: { layout: "minimal" } },
  { path: "/404", name: "not-found", component: NotFoundView, meta: { layout: "minimal" } },
  { path: "/:pathMatch(.*)*", redirect: "/404" },
];

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

router.beforeEach(createRouteGuard({ auth }));

export default router;
