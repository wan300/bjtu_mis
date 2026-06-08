# Remote Origin Demo

This example documents the v1 `allowed_origins` semantics. Declared origins are trusted executable and network origins: a page or iframe loaded from the origin may call the bridge with the service's granted permissions.

The remote URL is illustrative. It is not required for offline linting.

```powershell
node tools/third-party-service-lint.cjs third-party-plugins/examples/remote-origin-demo
```
