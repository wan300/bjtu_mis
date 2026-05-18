# BJTU MIS Android

BJTU MIS Android 是一款为北京交通大学同学准备的校园学习服务 App，方便在手机上查看 MIS / 教学服务中的常用信息。

你可以在应用里查看个人信息、课表、成绩、考试安排、作业、课程资源、空教室和校历，使用校园邮箱，离线查看已同步内容，接收作业提醒，也可以通过内置智能助手整理作业要求和相关资料。

## 目录结构

```text
.
├── android/                 # Kotlin + Jetpack Compose Android 工程
│   ├── app/                 # Android 应用源码、资源、测试
│   ├── open-webui/          # 嵌入到 Android WebView 的 Open WebUI 前端
│   ├── capacitor-android/   # 本地维护的 Capacitor Android bridge
│   ├── docs/                # Android 相关设计文档
│   ├── gradle/
│   └── README.md
├── test/automation/         # 辅助网络录制工具
├── .gitignore
└── README.md
```

## 功能概览

- 学业服务：个人信息、培养方案、成绩、课表、考试、学年日历、作业、选课、空教室、课程资源、课程回放。
- 校园邮箱：Coremail 文件夹、邮件列表、详情、联系人、草稿与发送能力。
- 本地数据：加密凭据、Cookie 持久化、Room 数据快照、后台同步、会话保活和作业提醒。
- 嵌入式 Open WebUI：作为应用内 Agent 界面运行，支持 local-first provider、原生 SSE、原生 HTTP、设备工具、网页搜索和安全存储桥接。
- 作业 Agent 协助：作业卡片可一键打开 Open WebUI Agent，自动传递作业要求、截止时间和用户补充说明。

## Agent 当前实现

- Android 端通过 `NativeAgentHomeworkHandoffStore` 暂存作业上下文，Open WebUI 启动后消费一次性 handoff 并生成待发送草稿。
- `NativeAgentToolsPlugin` 将 Android 工具注册给 Open WebUI local-first agent loop。当前暴露的工具覆盖工作区文件读写、常用归档解压与 ZIP 打包、PDF/DOCX 提取与生成、受限代码运行、Coremail 邮件能力和结果打包。
- 作业附件会自动下载并导入 Agent workspace。压缩包不在 handoff 阶段自动解压，Agent 需要通过 `agent_archive_extract` 按需解压到 `work/attachments/`。当前支持 `zip`/`jar`、`tar`、`tar.gz`/`tgz`、`tar.bz2`/`tbz2`、`gz` 和 `bz2`，并对路径穿越、条目数量、单文件大小和总大小做限制。
- Open WebUI 聊天界面会显示作业附件导入状态。附件准备期间会阻止提交，避免模型在文件未导入完成时开始分析。
- Coremail Agent 工具不依赖 workspace，可用于读取近期邮件、生成摘要上下文、搜索联系人、保存草稿。`agent_mail_send` 必须经过用户确认弹窗后才会真正发送。
- Agent 只能生成分析、步骤、答案草稿或 `output/` 下的文件，不会自动提交课程平台作业。
- 旧原生 Agent 的 Room 表、前台服务、API Key 和模型配置已废弃；应用启动时会清理遗留配置，数据库迁移到版本 7 时会删除旧 Agent 表。

## 环境要求

- Android Studio
- JDK 17
- Android SDK，项目当前 `compileSdk = 35`、`minSdk = 26`
- Node.js 与 npm，用于构建嵌入式 Open WebUI。`android/open-webui/package.json` 当前要求 Node `>=18.13.0 <=22.x.x`

## 开发与构建

用 Android Studio 打开 `android/`，等待 Gradle 同步后运行 `app` target。

命令行构建与测试：

```powershell
Set-Location android
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

`assembleDebug` 和 `assembleRelease` 会在 `preBuild` 阶段自动执行 Open WebUI 前端构建：安装依赖、运行移动端构建、同步 `open-webui/build` 到 `app/src/main/assets/public`，并移除 sourcemap。

Open WebUI 前端单独检查：

```powershell
Set-Location android\open-webui
npm ci
npm run check
npm run test:frontend -- --run
```

## 当前技术栈

- Kotlin、Jetpack Compose、Material 3
- OkHttp、Jsoup、kotlinx.serialization
- Room、DataStore、WorkManager
- Android Keystore 风格的本地安全存储
- PyTorch Android 验证码模型推理
- Capacitor WebView bridge、Open WebUI、Svelte、Vite
- AndroidX JavaScriptEngine 与 Chaquopy 受限代码运行
- Media3、PDFBox Android、FileProvider

## 说明

- Android 工程保留在 `android/` 下，不提升到仓库根目录。
- 验证码模型随 Android assets 打包，路径为 `android/app/src/main/assets/bjtu_captcha_crnn.pt`。
- 测试 fixture 随 Android 测试资源维护，路径为 `android/app/src/test/resources/fixtures/`。
- 本地 SDK 配置 `android/local.properties` 不提交；Android Studio 会按本机环境重新生成。
- `android/open-webui/node_modules/`、`android/open-webui/build/`、`android/app/src/main/assets/public/`、APK/AAB 和 Room schema 都是本机或构建生成内容，不应提交。
