import { createHash } from "node:crypto";
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

// Keep separately copied runtime assets inside the build-integrity scope.
const FRONTEND_RUNTIME_ASSETS = ["prototype.html"] as const;

export interface DevelopmentServerConfig {
  host: string;
  port: number;
  proxy: Record<string, { target: string; changeOrigin: boolean }>;
}

export interface FrontendBuildConfig {
  sourceRoot: string;
  outDir: string;
  emptyOutDir: true;
}

/**
 * Keep the Spring v1 route ahead of the wider Node compatibility route. The
 * browser always calls a same-origin path, so this is local-dev routing only.
 */
export function createDevelopmentServerConfig(env: Record<string, string | undefined>): DevelopmentServerConfig {
  const nodeOrigin = env.VITE_DEV_NODE_ORIGIN || "http://127.0.0.1:8791";
  const springOrigin = env.VITE_DEV_SPRING_ORIGIN || "http://127.0.0.1:8792";

  return {
    host: "0.0.0.0",
    port: Number(env.VITE_DEV_PORT || 5173),
    proxy: {
      "/api/v1": { target: springOrigin, changeOrigin: false },
      "/api": { target: nodeOrigin, changeOrigin: false },
      "/healthz": { target: nodeOrigin, changeOrigin: false },
      "/presentation": { target: nodeOrigin, changeOrigin: false },
      "/pdfs": { target: nodeOrigin, changeOrigin: false },
      "/vendor": { target: nodeOrigin, changeOrigin: false },
    },
  };
}

export function createFrontendBuildConfig(frontendRoot: string): FrontendBuildConfig {
  return {
    sourceRoot: path.join(frontendRoot, "src"),
    outDir: path.join(frontendRoot, "dist"),
    emptyOutDir: true,
  };
}

export function createBuildIntegrityPlugin(frontendRoot: string, outDir: string) {
  return {
    name: "frontend-build-integrity",
    closeBundle() {
      const manifest = {
        schemaVersion: 1,
        sourceHash: frontendSourceHash(frontendRoot),
      };
      writeFileSync(path.join(outDir, "build-integrity.json"), `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
    },
  };
}

function frontendSourceHash(frontendRoot: string): string {
  const sourceFiles = [
    ...collectFiles(path.join(frontendRoot, "src"), frontendRoot),
    "package.json",
    "package-lock.json",
    "tsconfig.json",
    "vite.config.ts",
    "vite-routing.ts",
    "vitest.config.ts",
    ...FRONTEND_RUNTIME_ASSETS,
  ].sort();
  const hash = createHash("sha256");
  for (const relativePath of sourceFiles) {
    hash.update(relativePath.replace(/\\/g, "/"));
    hash.update("\0");
    hash.update(readFileSync(path.join(frontendRoot, relativePath)));
    hash.update("\0");
  }
  return hash.digest("hex");
}

function collectFiles(directory: string, frontendRoot: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const fullPath = path.join(directory, entry.name);
    return entry.isDirectory()
      ? collectFiles(fullPath, frontendRoot)
      : [path.relative(frontendRoot, fullPath)];
  });
}
