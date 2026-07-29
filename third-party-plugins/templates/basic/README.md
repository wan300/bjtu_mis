# BJTU MIS Third-Party Service Template

This is a minimal static Manifest v3 Web plugin package for BJTU MIS Android.

## Structure

```text
bjtu-service.json
dist/
  index.html
  icon.svg
```

Replace `dist/icon.svg` with your own square plugin icon, or update the
`icon` field in `bjtu-service.json` to another path inside `dist/`.
Supported formats are SVG, PNG, WebP, JPG, and JPEG, with a maximum size of
1 MiB. The Android client and Web plugin marketplace display this file and
fall back to a built-in icon if it cannot be decoded.

Run the lint tool from the repository root:

```powershell
node tools/third-party-service-lint.cjs third-party-plugins/templates/basic
```

`bridge_origins` must stay exactly `["self"]`. Declare remote access in the
specific `connect_origins`, `media_origins`, `frame_origins`, or
`navigation_origins` list; public packages only accept HTTPS origins.
