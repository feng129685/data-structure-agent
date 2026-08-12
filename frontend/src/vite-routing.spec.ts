import { describe, expect, it } from "vitest";
import { createDevelopmentServerConfig, createFrontendBuildConfig } from "../vite-routing";

describe("Vite development routing", () => {
  it("routes v1 API requests to Spring before the legacy Node API", () => {
    const server = createDevelopmentServerConfig({
      VITE_DEV_NODE_ORIGIN: "http://127.0.0.1:8791",
      VITE_DEV_SPRING_ORIGIN: "http://127.0.0.1:8792",
    });
    const proxy = server.proxy;

    expect(Object.keys(proxy ?? {})).toEqual(expect.arrayContaining(["/api/v1", "/api"]));
    expect(Object.keys(proxy ?? {}).indexOf("/api/v1")).toBeLessThan(Object.keys(proxy ?? {}).indexOf("/api"));
    expect(proxy?.["/api/v1"]).toMatchObject({ target: "http://127.0.0.1:8792", changeOrigin: false });
    expect(proxy?.["/api"]).toMatchObject({ target: "http://127.0.0.1:8791", changeOrigin: false });
    expect(proxy?.["/api/v1"]).not.toHaveProperty("rewrite");
  });

  it("keeps the source entry separate from the stable production output directory", () => {
    const build = createFrontendBuildConfig("D:/workspace/frontend");

    expect(build.sourceRoot.replace(/\\/g, "/")).toMatch(/frontend\/src$/);
    expect(build.outDir.replace(/\\/g, "/")).toMatch(/frontend\/dist$/);
    expect(build.emptyOutDir).toBe(true);
  });
});
