# BJTU MIS Third-Party Service Template

This is a minimal SDK-first Manifest v3 / contract_v1 Web plugin source
fixture. Use `create-bjtu-plugin` for a complete Vanilla TypeScript + Vite
project.

## Structure

```text
bjtu-plugin.json
bjtu-marketplace.json
dist/
  index.html
  icon.svg
```

Replace `dist/icon.svg` with your own square plugin icon, or update the
`icon` field in `bjtu-plugin.json` to another path inside `dist/`.
Supported formats are SVG, PNG, WebP, JPG, and JPEG, with a maximum size of
1 MiB. The Android client and Web plugin marketplace display this file and
fall back to a built-in icon if it cannot be decoded.

Run the lint tool from the repository root:

```powershell
node tools/third-party-service-lint.cjs third-party-plugins/templates/basic --source --marketplace
```

The bridge origin is a host invariant and is not declared by plugins. Declare
only the required versioned capabilities. Optional capabilities are disabled
until the user grants them.
