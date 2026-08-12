import type { RouteLocationNormalized, RouteLocationRaw } from "vue-router";
import type { AuthStore } from "../shared/auth";
import type { AppRouteMeta } from "./route-meta";

export interface GuardRoute extends Pick<RouteLocationNormalized, "path" | "fullPath"> { meta: AppRouteMeta }

export function createRouteGuard(options: { auth: AuthStore }) {
  return async (to: GuardRoute): Promise<true | RouteLocationRaw> => {
    const auth = options.auth;
    if (auth.state.status === "idle" || auth.state.status === "restoring") await auth.restoreSession();
    const meta = to.meta || {};
    // A transport failure only preserves access when a previously restored user
    // is still present. Cold-start failures must not turn an unknown session
    // into an implicit grant for protected routes.
    const sessionIndeterminate = Boolean(auth.state.user)
      && (auth.state.status === "offline" || auth.state.status === "error");
    if (meta.requiresAuth && (!auth.state.user || auth.state.status !== "authenticated") && !sessionIndeterminate) {
      if (auth.state.status === "disabled") return { name: "forbidden", query: { reason: "disabled" } };
      if (auth.state.status === "forbidden") return { name: "forbidden" };
      return { name: "login", query: { redirect: to.fullPath || to.path } };
    }
    if (meta.roles?.length) {
      const hasRole = typeof auth.hasAnyRole === "function"
        ? auth.hasAnyRole(meta.roles)
        : Boolean(auth.state.user && meta.roles.some((role) => auth.state.user?.roles.includes(role)));
      // An outage may preserve an existing session, but it cannot grant roles
      // that are absent from the last known user record.
      if (!hasRole) return { name: "forbidden" };
    }
    if (meta.requiresCapability) {
      const capabilities = auth.state.capabilities || (typeof auth.loadCapabilities === "function" ? await auth.loadCapabilities() : null);
      if (!auth.state.user && !sessionIndeterminate) {
        if (auth.state.status === "disabled") return { name: "forbidden", query: { reason: "disabled" } };
        return { name: "login", query: { redirect: to.fullPath || to.path } };
      }
      if (!capabilities) {
        const errorStatus = auth.state.error?.status;
        if (errorStatus === 403 || auth.state.status === "forbidden") return { name: "forbidden" };
        return true;
      }
      const capability = capabilities?.modules?.[meta.requiresCapability];
      if (!capability?.available) return { name: "forbidden" };
    }
    return true;
  };
}
