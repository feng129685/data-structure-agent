import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": path.join(frontendRoot, "src"),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: [path.join(frontendRoot, "src/test/setup.ts")],
    include: ["src/**/*.spec.ts"],
    css: true,
  },
});
