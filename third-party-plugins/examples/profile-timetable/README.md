# Profile Timetable Example

This source fixture declares typed profile and timetable capabilities. Runtime
code uses `createBjtuPluginSdk()` and the `campus` namespace; the transport is
private and is never accessed through `window.BjtuService`.

```powershell
node tools/third-party-service-lint.cjs third-party-plugins/examples/profile-timetable --source --marketplace
```
