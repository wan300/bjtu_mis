import { defineConfig } from 'vite';
import { readFileSync } from 'node:fs';

interface DevelopmentConfig {
  mock?: Record<string, unknown> & {
    binary_supported?: boolean;
  };
  hmr?: {
    host?: string;
    port?: number;
  };
}

const development = JSON.parse(
  readFileSync(new URL('./bjtu-plugin.dev.json', import.meta.url), 'utf8')
) as DevelopmentConfig;
const androidHost = process.env.BJTU_ANDROID_HMR === '1';
const port = Number(process.env.BJTU_VITE_PORT ?? development.hmr?.port ?? 5173);
const host = development.hmr?.host ?? '127.0.0.1';
const mockScenario = {
  ...development.mock,
  ...(development.mock?.binary_supported === undefined
    ? {}
    : { binarySupported: development.mock.binary_supported })
};
delete mockScenario.binary_supported;

export default defineConfig({
  define: {
    __BJTU_PLUGIN_ANDROID_HOST__: JSON.stringify(androidHost),
    __BJTU_PLUGIN_MOCK_SCENARIO__: JSON.stringify(mockScenario)
  },
  server: {
    host,
    port,
    strictPort: true,
    hmr: androidHost
      ? {
          protocol: 'wss',
          clientPort: 443,
          path: '/__bjtu/dev-hmr'
        }
      : {
          host,
          port,
          protocol: 'ws'
        }
  },
  build: {
    target: 'es2022',
    sourcemap: true
  }
});
