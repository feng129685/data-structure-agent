import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import { viteSingleFile } from "vite-plugin-singlefile";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const sourceRoot = path.join(frontendRoot, "src");

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, frontendRoot, "");
  const apiOrigin = env.VITE_DEV_API_ORIGIN || "http://127.0.0.1:8791";

  return {
    root: sourceRoot,
    plugins: [vue(), ...(command === "build" ? [viteSingleFile()] : [])],
    resolve: {
      alias: {
        "@": sourceRoot,
      },
    },
    server: {
      host: "0.0.0.0",
      port: Number(env.VITE_DEV_PORT || 5173),
      proxy: {
        "/api": { target: apiOrigin, changeOrigin: false },
        "/healthz": { target: apiOrigin, changeOrigin: false },
        "/presentation": { target: apiOrigin, changeOrigin: false },
        "/pdfs": { target: apiOrigin, changeOrigin: false },
        "/vendor": { target: apiOrigin, changeOrigin: false },
      },
    },
    build: {
      outDir: frontendRoot,
      emptyOutDir: false,
      assetsInlineLimit: 100_000_000,
      rollupOptions: {
        input: path.join(sourceRoot, "index.html"),
      },
    },
  };
});
