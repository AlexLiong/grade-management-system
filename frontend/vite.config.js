import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import fs from "node:fs";
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    https: fs.existsSync("../.runtime/localhost.key")
      ? {
          key: fs.readFileSync("../.runtime/localhost.key"),
          cert: fs.readFileSync("../.runtime/localhost.crt"),
        }
      : undefined,
    proxy: {
      "/api": {
        target: "https://localhost:8443",
        secure: false,
        headers: { Origin: "https://localhost:8443" },
      },
      "/health": { target: "https://localhost:8443", secure: false },
    },
  },
});
