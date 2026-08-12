import { describe, expect, it } from "vitest";
import { resolveApiBaseUrl } from "./base-url";

describe("v1 API base URL", () => {
  it("uses the same-origin v1 path when development configuration is absent", () => {
    expect(resolveApiBaseUrl({ MODE: "development" })).toBe("/api/v1");
  });

  it("uses the same-origin v1 path in tests and production instead of a localhost fallback", () => {
    expect(resolveApiBaseUrl({ MODE: "test" })).toBe("/api/v1");
    expect(resolveApiBaseUrl({ MODE: "production" })).toBe("/api/v1");
  });

  it("normalizes an explicit v1 path and rejects an insecure production origin", () => {
    expect(resolveApiBaseUrl({ MODE: "development", VITE_API_BASE_URL: "/api/v1/" })).toBe("/api/v1");
    expect(() => resolveApiBaseUrl({
      MODE: "production",
      VITE_API_BASE_URL: "http://127.0.0.1:8791/api/v1",
    })).toThrow("VITE_API_BASE_URL");
  });
});
