import { reactive } from "vue";
import { ApiClientError, type ApiClient } from "../api/client";
import type { AdminCapability, AuthResponse, LoginRequest, RegisterRequest, RequestCodeRequest, ResetPasswordRequest, Role, User, VerificationCodeDelivery } from "../types";

export type AuthStatus = "idle" | "restoring" | "authenticated" | "anonymous" | "disabled" | "forbidden" | "offline" | "error";
export type CapabilityStatus = "unknown" | "loading" | "available" | "unavailable" | "forbidden";
export type AuthRuntimeError = Error & { code?: string; status?: number; requestId?: string; details?: string[] };
export type AuthErrorKind = "unauthorized" | "disabled" | "forbidden" | "not-found" | "rate-limited" | "unavailable" | "offline" | "timeout" | "server" | "unknown";
export interface AuthErrorClassification {
  kind: AuthErrorKind;
  retryAfterSeconds?: number;
}
export interface AuthState {
  status: AuthStatus;
  user: User | null;
  capabilities: AdminCapability | null;
  capabilityStatus: CapabilityStatus;
  capabilityError: ApiClientError | AuthRuntimeError | null;
  error: ApiClientError | AuthRuntimeError | null;
}
export interface AuthStore {
  readonly state: AuthState;
  readonly token: string | null;
  restoreSession(): Promise<void>;
  login(input: LoginRequest): Promise<User>;
  register(input: RegisterRequest): Promise<User>;
  resetPassword(input: ResetPasswordRequest): Promise<User>;
  requestCode(input: RequestCodeRequest): Promise<VerificationCodeDelivery>;
  logout(): Promise<void>;
  loadCapabilities(): Promise<AdminCapability | null>;
  handleUnauthorized(error?: unknown): void;
  hasRole(role: Role): boolean;
  hasAnyRole(roles: Role[]): boolean;
}

function responseData<T>(response: { kind: string; data?: T }): T {
  if (response.kind !== "json") throw new Error("接口未返回 JSON 数据");
  return response.data as T;
}

function asError(value: unknown): ApiClientError | AuthRuntimeError {
  return value instanceof Error ? value : new Error("网络请求失败");
}

/** Maps the stable API envelope to UI-safe auth states without treating outages as logout. */
export function classifyAuthError(value: unknown): AuthErrorClassification {
  const error = asError(value);
  const status = (value as { status?: number } | null)?.status;
  const code = (value as { code?: string } | null)?.code;
  const headers = (value as { headers?: Headers } | null)?.headers;
  const retryAfter = headers?.get("Retry-After") ?? headers?.get("retry-after");
  const retryAfterSeconds = retryAfter && /^\d+$/.test(retryAfter.trim())
    ? Number.parseInt(retryAfter.trim(), 10)
    : undefined;

  if (status === 401 || code === "AUTH_REQUIRED" || code === "AUTH_INVALID_CREDENTIALS") return { kind: "unauthorized" };
  if (code === "AUTH_USER_DISABLED" || code === "AUTH_DISABLED") return { kind: "disabled" };
  if (status === 403) return { kind: "forbidden" };
  if (status === 404) return { kind: "not-found" };
  if (status === 429) return { kind: "rate-limited", ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }) };
  if (status === 503) return { kind: "unavailable" };
  if (error.name === "AbortError" || code === "REQUEST_TIMEOUT" || code === "TIMEOUT") return { kind: "timeout" };
  if (error instanceof TypeError || /network|failed to fetch|fetch/i.test(error.message)) return { kind: "offline" };
  if (typeof status === "number" && status >= 500) return { kind: "server" };
  return { kind: "unknown" };
}

export function createAuthStore(options: { api: ApiClient; onTokenChange?: (token: string | null) => void }): AuthStore {
  const state = reactive<AuthState>({
    status: "idle",
    user: null,
    capabilities: null,
    capabilityStatus: "unknown",
    capabilityError: null,
    error: null,
  });
  let token: string | null = null;

  const clear = (status: AuthStatus = "anonymous", error: AuthState["error"] = null) => {
    token = null;
    options.onTokenChange?.(null);
    state.user = null;
    state.capabilities = null;
    state.capabilityStatus = "unknown";
    state.capabilityError = null;
    state.status = status;
    state.error = error;
  };

  const applyAuthResponse = (response: AuthResponse): User => {
    token = response.token || null;
    options.onTokenChange?.(token);
    state.user = response.user;
    state.capabilities = null;
    state.capabilityStatus = "unknown";
    state.capabilityError = null;
    state.status = "authenticated";
    state.error = null;
    return response.user;
  };

  const handleFailure = (failure: unknown, preserveForbiddenSession = false) => {
    const error = asError(failure);
    const classification = classifyAuthError(failure);
    if (classification.kind === "unauthorized") {
      clear("anonymous", error);
    } else if (classification.kind === "disabled") {
      clear("disabled", error);
    } else if (classification.kind === "forbidden") {
      state.status = preserveForbiddenSession && state.user ? "authenticated" : "forbidden";
      state.error = error;
    } else if (classification.kind === "offline") {
      state.status = "offline";
      state.error = error;
    } else {
      state.status = "error";
      state.error = error;
    }
  };

  async function restoreSession(): Promise<void> {
    state.status = "restoring";
    state.error = null;
    try {
      const response = await options.api.request<User>("/users/me");
      state.user = responseData(response);
      state.status = "authenticated";
      state.capabilities = null;
      state.capabilityStatus = "unknown";
      state.capabilityError = null;
    } catch (failure) {
      handleFailure(failure);
    }
  }

  async function login(input: LoginRequest): Promise<User> {
    try {
      return applyAuthResponse(responseData(await options.api.request<AuthResponse>("/auth/login", { method: "POST", body: JSON.stringify(input) })));
    } catch (failure) {
      handleFailure(failure);
      throw failure;
    }
  }

  async function register(input: RegisterRequest): Promise<User> {
    try {
      return applyAuthResponse(responseData(await options.api.request<AuthResponse>("/auth/register", { method: "POST", body: JSON.stringify(input) })));
    } catch (failure) {
      handleFailure(failure);
      throw failure;
    }
  }

  async function resetPassword(input: ResetPasswordRequest): Promise<User> {
    try {
      return applyAuthResponse(responseData(await options.api.request<AuthResponse>("/auth/reset-password", { method: "POST", body: JSON.stringify(input) })));
    } catch (failure) {
      handleFailure(failure);
      throw failure;
    }
  }

  async function requestCode(input: RequestCodeRequest): Promise<VerificationCodeDelivery> {
    return responseData(await options.api.request<VerificationCodeDelivery>("/auth/request-code", { method: "POST", body: JSON.stringify(input) }));
  }

  async function logout(): Promise<void> {
    try { await options.api.request("/auth/logout", { method: "POST" }); } finally { clear("anonymous"); }
  }

  async function loadCapabilities(): Promise<AdminCapability | null> {
    if (!state.user) return null;
    state.capabilityStatus = "loading";
    state.capabilityError = null;
    try {
      const value = responseData(await options.api.request<AdminCapability>("/admin/capabilities"));
      state.capabilities = value;
      state.capabilityStatus = "available";
      state.capabilityError = null;
      state.status = "authenticated";
      state.error = null;
      return value;
    } catch (failure) {
      state.capabilities = null;
      const error = asError(failure);
      const classification = classifyAuthError(failure);
      handleFailure(failure, true);
      if (classification.kind === "forbidden") {
        state.capabilityStatus = "forbidden";
        state.capabilityError = error;
      } else if (classification.kind !== "unauthorized" && classification.kind !== "disabled") {
        state.capabilityStatus = "unavailable";
        state.capabilityError = error;
      }
      return null;
    }
  }

  function handleUnauthorized(error?: unknown): void {
    if (!error) {
      clear("anonymous");
      return;
    }
    handleFailure(error);
  }

  return {
    state,
    get token() { return token; },
    restoreSession,
    login,
    register,
    resetPassword,
    requestCode,
    logout,
    loadCapabilities,
    handleUnauthorized,
    hasRole: (role: Role) => Boolean(state.user?.roles.includes(role)),
    hasAnyRole: (roles: Role[]) => Boolean(state.user && roles.some((role) => state.user?.roles.includes(role))),
  };
}
