# Remote frame example

This Manifest v3 example declares `https://example.com` only in
`frame_origins`. The frame is sandboxed with scripts, forms, and same-origin
DOM/storage support, but it cannot access the BJTU native bridge, navigate the
top frame, open popups, or download files.

Run:

```powershell
node tools/third-party-service-lint.cjs third-party-plugins/examples/remote-origin-demo
```
