# BJTU MIS Android

这是 BJTU MIS 的原生 Android 工程。应用不依赖 PC 本地 HTTP 服务，也不需要云端自建后端；它直接访问 BJTU CAS、MIS、AA、VE 等系统，并在设备本地保存加密凭据、Cookie 与 Room 数据快照。

## 打开工程

1. 用 Android Studio 打开本目录 `android/`。
2. 等待 Gradle 同步完成。
3. 运行 `app` target。

## 命令行

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

## 关键路径

- `app/src/main/java/cn/edu/bjtu/mis/`：Android 应用源码
- `app/src/main/python/agent_runner.py`：Chaquopy 本地 Python runner
- `app/src/main/assets/bjtu_captcha_crnn.pt`：验证码识别模型
- `app/src/test/resources/fixtures/`：解析器与 Provider 单元测试 fixture
- `docs/android-local-agent-design.md`：本地 Agent 设计文档

## 本地文件

`local.properties`、Gradle 缓存、APK/AAB、release 输出和 Room schema 输出都是本机或构建生成内容，不应提交。
