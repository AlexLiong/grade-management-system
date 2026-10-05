import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import fs from "node:fs";
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    /* 忽略编辑器/工具写入源码时产生的临时目录：它们在写完后立刻消失，
       文件监听器可能在半途抓到句柄，Windows 上会抛 EBUSY 并让 dev server 退出。 */
    watch: {
      ignored: ["**/.*.tmpdir/**", "**/*.tmp"],
    },
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
