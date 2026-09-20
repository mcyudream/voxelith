import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

// 开发期代理目标，可用 MAP_SERVER_URL 覆盖（默认本机 8090 的 voxelith-server；
// 8080/8081 被 WSL 的 wslrelay.exe 长期占用，连上去只会被直接掐断，别指过去）
const mapServerUrl = process.env.MAP_SERVER_URL ?? "http://localhost:8090";

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
