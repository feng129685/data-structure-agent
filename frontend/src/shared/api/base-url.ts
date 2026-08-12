export interface ApiRuntimeEnvironment {
  MODE?: string;
  VITE_API_BASE_URL?: string;
}

const V1_PATH = "/api/v1";

/**
 * Resolves the browser-facing Spring API endpoint. Local development stays
 * same-origin so Vite can route it to Spring without baking a localhost URL
 * into a production bundle.
 */
export function resolveApiBaseUrl(environment: ApiRuntimeEnvironment = import.meta.env): string {
  const configured = environment.VITE_API_BASE_URL?.trim();
  if (!configured) return V1_PATH;

  if (configured.startsWith("/")) {
    return normalizeV1Path(configured);
  }

  let url: URL;
  try {
    url = new URL(configured);
  } catch {
    throw new Error("VITE_API_BASE_URL must be a same-origin path or an absolute v1 API URL");
  }

  if (url.username || url.password || url.search || url.hash) {
    throw new Error("VITE_API_BASE_URL must not include credentials, query parameters, or a fragment");
  }
  if (environment.MODE === "production" && url.protocol !== "https:") {
    throw new Error("VITE_API_BASE_URL must use HTTPS in production");
  }

  return `${url.origin}${normalizeV1Path(url.pathname)}`;
}

function normalizeV1Path(value: string): string {
  const normalized = value.replace(/\/+$/, "") || "/";
  if (normalized !== V1_PATH && !normalized.startsWith(`${V1_PATH}/`)) {
    throw new Error("VITE_API_BASE_URL must target /api/v1");
  }
  return normalized;
}
