import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

// 开发期代理目标，可用 MAP_SERVER_URL 覆盖（默认本机 8081 的 voxelith-server；8080 为其他服务占用）
const mapServerUrl = process.env.MAP_SERVER_URL ?? "http://localhost:8081";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: mapServerUrl,
        changeOrigin: true,
      },
      "/maps": {
        target: mapServerUrl,
        changeOrigin: true,
      },
    },
  },
});
