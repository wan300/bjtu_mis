# BJTU MIS Android

这是 BJTU MIS 的原生 Android 工程。应用不依赖 PC 本地 HTTP 服务，也不需要云端自建后端；它直接访问 BJTU CAS、MIS、AA、VE、Coremail 等系统，并在设备本地保存加密凭据、Cookie 与 Room 数据快照。

智能助手入口已经统一为嵌入式 Open WebUI Agent。旧的独立原生作业 Agent 任务系统、Agent Room 表、原生前台 Agent 服务、模型配置和 API Key 存储已移除。

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

前端单独检查：

```powershell
Set-Location open-webui
npm ci
npm run check
npm run test:frontend -- --run
```

## Agent 集成

- `OpenWebUiAgentScreen` 在 Compose 内嵌 `OpenWebUiAgentFragment`，通过 Capacitor WebView 加载打包后的 Open WebUI。
- Fragment 注册 `NativeSsePlugin`、`NativeWebSearchPlugin`、`NativeSecureStorePlugin`、`NativeAndroidToolsPlugin`、`NativeAgentToolsPlugin` 和 `NativeHttpPlugin`。
- 作业列表中的“Agent 协助”按钮会写入 `NativeAgentHomeworkHandoffStore`，然后跳转到 Open WebUI Agent。
- Open WebUI 启动后消费 pending handoff，生成作业分析草稿，并在请求参数中携带 `agent_workspace_id`。
- Android 端自动下载作业附件并导入 Agent workspace。压缩包不在 handoff 阶段自动解压，Agent 需要通过 `agent_archive_extract` 按需解压到 `work/attachments/`。当前支持 `zip`/`jar`、`tar`、`tar.gz`/`tgz`、`tar.bz2`/`tbz2`、`gz` 和 `bz2`，并限制路径穿越、条目数量、单文件大小和总大小。
- Open WebUI local-first agent loop 会根据 `agent_workspace_id` 动态挂载 Android 原生 `agent_*` 工具。邮件工具不需要 workspace，其他文件、归档、文档和结果打包工具需要 workspace。
- `agent_mail_send` 发送前必须经过 Open WebUI 确认弹窗；作业 Agent 不会自动提交课程平台作业。

## 原生工具

`NativeAgentToolsPlugin` 当前向 Open WebUI 暴露以下工具名：

- 工作区文件：`agent_file_list`、`agent_file_read`、`agent_file_write`、`agent_file_delete`
- 归档：`agent_archive_extract`、`agent_archive_create_zip`
- 文档：`agent_document_extract_pdf`、`agent_document_extract_docx`、`agent_document_generate_pdf`、`agent_document_generate_docx`
- 代码：`agent_run_javascript`
- 邮件：`agent_mail_list_folders`、`agent_mail_list_recent`、`agent_mail_read`、`agent_mail_digest_context`、`agent_mail_search_contacts`、`agent_mail_save_draft`、`agent_mail_send`
- 结果：`agent_package_results`

## 关键路径

- `app/src/main/java/cn/edu/bjtu/mis/`：Android 应用源码。
- `app/src/main/java/cn/edu/bjtu/mis/openwebui/OpenWebUiAgentFragment.kt`：嵌入式 Open WebUI Fragment 与 Capacitor 插件注册。
- `app/src/main/java/cn/edu/bjtu/mis/openwebui/NativeAgentToolsPlugin.kt`：Open WebUI 到 Android 原生 Agent 工具的桥接。
- `app/src/main/java/cn/edu/bjtu/mis/openwebui/NativeAgentHomeworkHandoffStore.kt`：作业上下文的一次性 handoff。
- `app/src/main/java/cn/edu/bjtu/mis/data/agent/tools/`：工作区、文件、归档、文档、代码、邮件和结果打包工具。
- `app/src/main/python/agent_runner.py`：Chaquopy 本地 Python runner。
- `app/src/main/assets/bjtu_captcha_crnn.pt`：验证码识别模型。
- `open-webui/`：嵌入到 Android WebView 的 Open WebUI 前端源码。
- `open-webui/src/lib/local-first/`：local-first provider、Agent loop、Android 原生工具注册和 homework handoff 逻辑。
- `app/src/test/resources/fixtures/`：解析器与 Provider 单元测试 fixture。
- `docs/android-local-agent-design.md`：当前 Agent 集成边界说明。

## 数据迁移

- Room 数据库当前版本为 7。
- `MIGRATION_6_7` 会删除旧原生 Agent 的 `agent_tasks`、`agent_steps`、`agent_artifacts` 和 `agent_messages` 表。
- 应用启动时会调用 `clearLegacyNativeAgentConfiguration`，清理旧 Agent API Key、DataStore 设置和 Android Keystore alias。

## 本地文件

`local.properties`、Gradle 缓存、APK/AAB、release 输出、Room schema、`open-webui/node_modules/`、`open-webui/build/`、`.svelte-kit/` 和 `app/src/main/assets/public/` 都是本机或构建生成内容，不应提交。
