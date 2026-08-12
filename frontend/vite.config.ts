import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import { viteSingleFile } from "vite-plugin-singlefile";
import { createBuildIntegrityPlugin, createDevelopmentServerConfig, createFrontendBuildConfig } from "./vite-routing";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));

export function createViteConfig(command: string, env: Record<string, string | undefined>) {
  const build = createFrontendBuildConfig(frontendRoot);

  return {
    root: build.sourceRoot,
    plugins: [
      vue(),
      ...(command === "build" ? [viteSingleFile(), createBuildIntegrityPlugin(frontendRoot, build.outDir)] : []),
    ],
    resolve: {
      alias: {
        "@": build.sourceRoot,
      },
    },
    server: createDevelopmentServerConfig(env),
    build: {
      outDir: build.outDir,
      emptyOutDir: build.emptyOutDir,
      assetsInlineLimit: 100_000_000,
      rollupOptions: {
        input: path.join(build.sourceRoot, "index.html"),
      },
    },
  };
}

export default defineConfig(({ command, mode }) => createViteConfig(command, loadEnv(mode, frontendRoot, "")));
