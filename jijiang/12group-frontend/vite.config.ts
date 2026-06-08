import { defineConfig, loadEnv } from "vite";
import uni from "@dcloudio/vite-plugin-uni";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || "http://47.95.238.140:8080";

  return {
    plugins: [uni()],
    server: {
      proxy: {
        "/api": {
          target: apiProxyTarget,
          changeOrigin: true,
        },
        "/actuator": {
          target: apiProxyTarget,
          changeOrigin: true,
        },
      },
    },
  };
});
