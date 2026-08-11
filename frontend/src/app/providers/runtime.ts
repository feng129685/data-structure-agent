import { createApiClient } from "../../shared/api";
import { createAuthStore } from "../../shared/auth";

let bearerToken: string | null = null;
export const api = createApiClient({ tokenProvider: () => bearerToken });
export const auth = createAuthStore({ api, onTokenChange: (token) => { bearerToken = token; } });
