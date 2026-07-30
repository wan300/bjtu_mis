# @bjtu-mis/plugin-sdk

Typed SDK for BJTU MIS Manifest v3 `contract_v1` plugins.

```ts
import { createBjtuPluginSdk } from '@bjtu-mis/plugin-sdk';

const bjtu = createBjtuPluginSdk();
await bjtu.runtime.handshake();
const timetable = await bjtu.campus.getTimetable();
```

The SDK speaks protocol v2, exposes generated request/response and lifecycle-event
types, converts host errors to `BjtuPluginError`, propagates `AbortSignal`, enforces
generated request deadlines, and keeps the WebView transport private. A `back`
listener returns `true` when it consumes navigation. Cache resources returned by
network requests can be retained with `cache.promote()` or released with
`cache.deleteHandle()`.

Persistent state must use SDK KV/Blob/Cache APIs. Contract runtimes install their
managed-storage guard before plugin code and do not expose the bridge if that
guard cannot disable browser-managed persistence. Zero-byte Blob and Cache
values remain valid.

Migration entrypoints use the restricted `createBjtuPluginMigrationSdk()` client. It exposes
only shadow `storage.kv@2` operations and an explicit `commit()`; normal runtime, network, and
command capabilities are unavailable during migration.
