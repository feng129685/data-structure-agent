import { reactive } from "vue";
import { ApiClientError, type ApiClient } from "../api/client";
import type { AdminCapability, AuthResponse, LoginRequest, RegisterRequest, RequestCodeRequest, ResetPasswordRequest, Role, User, VerificationCodeDelivery } from "../types";

export type AuthStatus = "idle" | "restoring" | "authenticated" | "anonymous" | "disabled" | "offline" | "error";
export type AuthRuntimeError = Error & { code?: string; status?: number; requestId?: string; details?: string[] };
export interface AuthState {
  status: AuthStatus;
  user: User | null;
  capabilities: AdminCapability | null;
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

export function createAuthStore(options: { api: ApiClient; onTokenChange?: (token: string | null) => void }): AuthStore {
  const state = reactive<AuthState>({ status: "idle", user: null, capabilities: null, error: null });
  let token: string | null = null;

  const clear = (status: AuthStatus = "anonymous", error: AuthState["error"] = null) => {
    token = null;
    options.onTokenChange?.(null);
    state.user = null;
    state.capabilities = null;
    state.status = status;
    state.error = error;
  };

  const applyAuthResponse = (response: AuthResponse): User => {
    token = response.token || null;
    options.onTokenChange?.(token);
    state.user = response.user;
    state.capabilities = null;
    state.status = "authenticated";
    state.error = null;
    return response.user;
  };

  const handleFailure = (failure: unknown, duringRestore = false) => {
    const error = asError(failure);
    const status = (failure as { status?: number } | null)?.status;
    const code = (failure as { code?: string } | null)?.code;
    if (status === 401 || code === "AUTH_REQUIRED" || code === "AUTH_INVALID_CREDENTIALS") {
      clear("anonymous", status === 401 ? error : error);
    } else if (status === 403 && (code === "AUTH_USER_DISABLED" || code === "AUTH_DISABLED")) {
      clear("disabled", error);
    } else if (error instanceof TypeError || /network|failed to fetch|fetch/i.test(error.message)) {
      state.status = "offline";
      state.error = error;
      if (duringRestore) state.user = null;
    } else {
      state.status = "error";
      state.error = error;
      if (duringRestore) state.user = null;
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
    } catch (failure) {
      handleFailure(failure, true);
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
    if (state.status !== "authenticated" || !state.user) return null;
    try {
      const value = responseData(await options.api.request<AdminCapability>("/admin/capabilities"));
      state.capabilities = value;
      return value;
    } catch (failure) {
      const error = asError(failure);
      if ((failure as { status?: number } | null)?.status === 403) state.capabilities = null;
      else state.error = error;
      return null;
    }
  }

  function handleUnauthorized(error?: unknown): void {
    if ((error as { code?: string } | null)?.code === "AUTH_USER_DISABLED") clear("disabled", asError(error));
    else clear("anonymous", error ? asError(error) : null);
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
