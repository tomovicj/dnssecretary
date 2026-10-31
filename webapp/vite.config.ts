/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// npm run dev  – the real backend (sh scripts/web.sh) behind /api
// npm run mock – no backend: src/api/mock.ts answers (mode "mock", see .env.mock)
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
  test: {
    environment: "jsdom",
  },
});
