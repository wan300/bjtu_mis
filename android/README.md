# BJTU MIS Android

这是 BJTU MIS 的原生 Android 工程。应用不依赖 PC 本地 HTTP 服务，也不需要云端自建后端；它直接访问 BJTU CAS、MIS、AA、VE 等系统，并在设备本地保存加密凭据、Cookie 与 Room 数据快照。

## 打开工程

1. 用 Android Studio 打开 `android/`。
2. 等待 Gradle 同步完成。
3. 运行 `app` target。

## 命令行

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

`assembleDebug` 和 `assembleRelease` 会在 `preBuild` 阶段自动构建嵌入的 Open WebUI 前端：

1. 在 `open-webui/` 执行 `npm ci`。
2. 使用 `ENABLE_MOBILE_CLIENT=true` 和 `ENABLE_MOBILE_NATIVE_FEATURES=true` 执行 `npm run build`。
3. 将 `open-webui/build` 同步到 `app/src/main/assets/public`。
4. 删除 sourcemap，并生成空的 `cordova.js` / `cordova_plugins.js` 兼容文件。

## 关键路径

- `app/src/main/java/cn/edu/bjtu/mis/`：Android 应用源码。
- `app/src/main/python/agent_runner.py`：Chaquopy 本地 Python runner。
- `app/src/main/assets/bjtu_captcha_crnn.pt`：验证码识别模型。
- `open-webui/`：嵌入到 Android WebView 的 Open WebUI 前端源码。
- `app/src/test/resources/fixtures/`：解析器与 Provider 单元测试 fixture。
- `docs/android-local-agent-design.md`：本地 Agent 设计文档。

## 本地文件

`local.properties`、Gradle 缓存、APK/AAB、release 输出、Room schema、`open-webui/node_modules/`、`open-webui/build/`、`.svelte-kit/` 和 `app/src/main/assets/public/` 都是本机或构建生成内容，不应提交。
