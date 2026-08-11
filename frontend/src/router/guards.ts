import type { RouteLocationNormalized, RouteLocationRaw } from "vue-router";
import type { AuthStore } from "../shared/auth";
import type { AppRouteMeta } from "./route-meta";

export interface GuardRoute extends Pick<RouteLocationNormalized, "path" | "fullPath"> { meta: AppRouteMeta }

export function createRouteGuard(options: { auth: AuthStore }) {
  return async (to: GuardRoute): Promise<true | RouteLocationRaw> => {
    const auth = options.auth;
    if (auth.state.status === "idle" || auth.state.status === "restoring") await auth.restoreSession();
    const meta = to.meta || {};
    if (meta.requiresAuth && auth.state.status !== "authenticated") {
      if (auth.state.status === "disabled") return { name: "forbidden", query: { reason: "disabled" } };
      return { name: "login", query: { redirect: to.fullPath || to.path } };
    }
    if (meta.roles?.length) {
      const hasRole = typeof auth.hasAnyRole === "function"
        ? auth.hasAnyRole(meta.roles)
        : Boolean(auth.state.user && meta.roles.some((role) => auth.state.user?.roles.includes(role)));
      if (!hasRole) return { name: "forbidden" };
    }
    if (meta.requiresCapability) {
      const capabilities = auth.state.capabilities || (typeof auth.loadCapabilities === "function" ? await auth.loadCapabilities() : null);
      const capability = capabilities?.modules?.[meta.requiresCapability];
      if (!capability?.available) return { name: "forbidden" };
    }
    return true;
  };
}
