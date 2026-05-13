# BJTU MIS Android

BJTU MIS Android 是面向北京交通大学 MIS / 教学服务相关系统的原生 Android 应用。

项目已经剥离原来的 PC 本地服务和浏览器控制台实现。后续开发集中在 `android/` 子工程内，应用直接访问 BJTU CAS、MIS、AA、VE 等系统，并在本机使用加密存储、Room 快照和 WorkManager 支持离线查看与后台同步。

## 目录结构

```text
.
├── android/        # Kotlin + Jetpack Compose Android 工程
│   ├── app/
│   ├── docs/       # Android 相关设计文档
│   ├── gradle/
│   └── README.md
├── .gitignore
└── README.md
```

## 环境要求

- Android Studio
- JDK 17
- Android SDK，项目当前 `compileSdk = 35`、`minSdk = 26`

## 开发与构建

用 Android Studio 打开 `android/`，等待 Gradle 同步后运行 `app` target。

命令行构建与测试：

```powershell
Set-Location android
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

## 当前技术栈

- Kotlin、Jetpack Compose、Material 3
- OkHttp、Jsoup、kotlinx.serialization
- Room、DataStore、WorkManager
- Android Keystore 风格的本地安全存储
- PyTorch Android 验证码模型推理
- AndroidX JavaScriptEngine 与 Chaquopy 支撑本地 Agent 小代码运行
- Media3、PDFBox Android、FileProvider

## 说明

- Android 工程保留在 `android/` 下，不提升到仓库根目录。
- 验证码模型随 Android assets 打包，路径为 `android/app/src/main/assets/bjtu_captcha_crnn.pt`。
- 测试 fixture 随 Android 测试资源维护，路径为 `android/app/src/test/resources/fixtures/`。
- 本地 SDK 配置 `android/local.properties` 不提交；Android Studio 会按本机环境重新生成。
