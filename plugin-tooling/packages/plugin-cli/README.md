# @bjtu-mis/plugin-cli

Commands:

- `bjtu create`
- `bjtu dev`
- `bjtu lint`
- `bjtu test`
- `bjtu pack`
- `bjtu doctor`
- `bjtu inspect`
- `bjtu migrate`

The CLI validates `bjtu-plugin.json`, keeps `bjtu-plugin.dev.json` out of
archives, and creates byte-for-byte deterministic ZIP packages. `bjtu test`
loads the built entrypoint in a real headless Chrome/Chromium/Edge process with
a protocol v2 Mock Host. Lint and pack enforce the same package, marketplace,
configuration, iframe, icon, file-count, and byte limits as the platform.
