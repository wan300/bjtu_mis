import { BjtuPluginError, createBjtuPluginSdk } from '@bjtu-mis/plugin-sdk';
import type { MockHostScenario } from '@bjtu-mis/plugin-sdk/mock';
import './style.css';

declare const __BJTU_PLUGIN_ANDROID_HOST__: boolean;
declare const __BJTU_PLUGIN_MOCK_SCENARIO__: MockHostScenario;

const status = document.querySelector<HTMLParagraphElement>('#status');

async function start(): Promise<void> {
  const bjtu =
    import.meta.env.DEV && !__BJTU_PLUGIN_ANDROID_HOST__
      ? (await import('@bjtu-mis/plugin-sdk/mock')).createMockSdk(
          __BJTU_PLUGIN_MOCK_SCENARIO__
        )
      : createBjtuPluginSdk();
  const runtime = await bjtu.runtime.handshake();
  await bjtu.runtime.ready();
  if (status) {
    status.textContent = `Connected to ${runtime.contractProfile} / protocol v${runtime.protocolVersion}.`;
  }
}

start().catch((error: unknown) => {
  if (!status) return;
  status.textContent =
    error instanceof BjtuPluginError
      ? `${error.code}: ${error.message}`
      : error instanceof Error
        ? error.message
        : String(error);
});
