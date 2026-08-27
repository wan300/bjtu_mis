# ExecPlan: 插件持久 Android 通用原生 Capability

## 1. Purpose and user-visible outcome

将已获一次性安装/增量审阅授权的通用 Android Capability 提供给 Manifest v3 /
contract_v1 插件。授权会按 publisher subject + plugin ID 持久保存；具有持久订阅或调度任务的
插件可以在后台 WebView runtime 中继续接收受限事件和调用不需要系统 UI 的 API。用户仍可在
插件管理页和持续通知中停止或撤销。

本计划包含以下 beta Capability，全部使用 protocol v2、`runtimeFloor = 2`：

- `android.device.info@1`、`android.network.status@1`、`android.battery.status@1`
- `android.haptics.perform@1`
- `android.files.pick@1`、`android.files.save@1`、`android.media.pick@1`、`android.share.open@1`
- `android.notifications.post@1`
- `android.location.read@1`
- `android.calendar.read@1`、`android.calendar.write@1`
- `android.camera.capture@1`、`android.audio.record@1`
- `android.sensors.read@1`、`android.biometric.verify@1`

成功标准：所有 API 来自 Contract Registry，SDK 和 Mock Host 具有强类型调用；持久授权不再
显示宿主逐次确认；系统 UI、运行时权限和前台服务限制仍由 Android 强制；撤销、禁用、删除、
publisher 不匹配、声明删除或 `needsReview` 会立即停止后台 runtime、订阅、录音和待处理 UI 调用。

## 2. Constraints and non-goals

- 保留 Manifest v3 / contract_v1、publisher+plugin 隔离、稳定本地 origin、main-frame-only bridge、
  远程 frame 无桥、分域联网和只读校园代理。
- “持久后台可用”表示授权与允许的订阅/调度持久化，不代表绕过 Android 的前台或用户界面限制。
  文件、媒体、分享、相机和生物识别只能从前台 runtime 打开系统 UI；后台调用返回
  `foreground_required`。定位不请求 `ACCESS_BACKGROUND_LOCATION`；录音只能在可见前台 runtime
  中开始，并受麦克风前台服务/系统隐私指示约束。
- 不引入联系人、剪贴板读取、短信、电话、通知监听、MediaProjection、Overlay、VPN、UsageStats、
  任意 Intent、任意 URI、任意文件路径、全盘存储、Shell/ADB、账号/凭据或安装 APK API。
- 文件和媒体结果进入现有 publisher+plugin 隔离的加密 `storage.blob`；不暴露原始可持续 URI。
- 不记录文件内容、位置坐标、音频、传感器样本、生物识别信息或系统 UI 返回文本。命令回执只保留
  加密请求摘要与最小元数据。
- 不修改或回退用户已有 `BjtuMisApp.kt`、`web/index.html` 或无关 worktree 改动。

## 3. Governance and authorization model

一次性 Capability 审阅保存授权；Android runtime permission 仍在首次可见调用时按平台要求请求。
每一项可从插件管理页撤销，更新新增 Capability 时进入 `needsReview`。以下规则在 runtime 强制：

- `network`、`battery`、`sensors` 的订阅可标记 `persistent`，每插件最多 16 个；网络/电池每订阅
  最多 60 events/s，传感器最多 20 Hz。它们可维持最多四个后台 runtime，并有持续通知与全部停止。
- 设备、网络和电池信息不包含硬件稳定 ID、SSID、BSSID、MAC、IP 或精确网络名称。
- `haptics` 时长最多 1 秒且不在循环中运行；通知每插件每小时 60 条，使用插件隔离 channel。
- 文件/媒体最多 16 个项目、单项 64 MiB；仅系统选择器结果可读取。保存使用系统创建文档面板。
- 分享仅允许文本、HTTPS URL 或当前插件 blob handle，且必经 Android chooser。
- 日历读取限制为 366 天范围/200 条；写入动作要求 idempotency key 与加密回执，但不再弹宿主
  确认。所有日历字段上限由 Contract Schema 固定。
- 定位只返回一次前台位置、最长 60 秒；不启动后台监听。相机仅系统 capture UI；录音仅可见前台
  runtime，单段最长 10 分钟，停止后存入加密 blob。生物识别只返回成功、取消或不可用状态。

## 4. Proposed design

1. 在 Contract Registry 增加请求、响应、事件、错误、配额、超时、`beta` metadata，并生成 Kotlin、
   TypeScript、平台类型、Manifest schema、SDK 和 API 文档。
2. 新建 `AndroidNativeCapabilityProvider`，用现有 `ThirdPartyResourceStore` 保存受控文件结果，
   使用 `PluginNativeInteractionActivity` 协调只有前台可执行的系统 UI 和运行时权限。
3. 扩展 `PluginAutomationStore` 保存通用持久订阅，`PluginAutomationSupervisor` 只在存有允许的
   持久订阅时恢复 runtime；无障碍服务不再是通用后台能力的前置条件。前台页面仍优先接收事件。
4. 将通用 provider 注入 `ThirdPartyServiceApiRegistry` 和 `AppContainer`；对已授权的列举原生
   Command Capability 复用加密幂等 receipt，但跳过宿主 confirmer。
5. 更新 Capability 审阅和管理 UI，展示 Android runtime permission、前台/后台可用性、系统 UI
   限制、活跃订阅、停止和撤销入口。

## 5. Files and components

- `.specify/memory/constitution.md`、`AGENTS.md`、`PLANS.md` 和 Spec Kit 模板：治理与测试规则。
- `plugin-tooling/contracts/capability-contracts.json`：唯一 API 权威源；生成器派生全部公开制品。
- `AndroidNativeCapabilities.kt`、`PluginNativeInteractionActivity.kt`：权限、系统 UI、资源和事件
  provider。
- `PluginAutomationStore.kt`、`PluginAutomationSupervisor.kt`、`ThirdPartyServiceApiRegistry.kt`、
  `AppContainer.kt`：持久订阅、后台 runtime、依赖注入和免逐次 confirmer。
- `AndroidManifest.xml`、`res/values/strings.xml`：Activity、必要的 normal/runtime 权限和前台服务
  类型；不申请后台定位或全盘存储。
- `ThirdPartyServiceScreens.kt`、开发者文档、tooling/Android/平台测试：审阅、可见状态、规范和验证。

## 6. Validation plan

- Tooling：`npm.cmd run generate:check`、`npm.cmd run typecheck`、`npm.cmd test`、
  `npm.cmd run pack:check`。
- Android：`gradlew.bat test`、`:app:compileDebugAndroidTestKotlin`、`assembleDebug`；覆盖权限拒绝、
  前台 gate、无 raw URI、配额、订阅恢复、撤销/更新/删除清理、命令 idempotency 和资源隔离。
- Platform：typecheck、unit、integration、e2e；Manifest lint 和 schema 镜像一致。
- Instrumentation：API 26/35 检验系统选择器、权限、录音/相机、生物识别、前后台 runtime 与通知。
  本机没有可用设备时记录为阻塞 CI。

## 7. Rollback

移除新 Capability descriptor/provider/Activity/Manifest 权限后重新生成制品；旧客户端不会识别或
执行新的加密订阅记录。当前版本在撤销或删除时清理 subscription、runtime、临时 capture 与 blob
引用。若设备权限已授予，用户可在 Android 系统设置随时收回；本计划不涉及 Room migration。

## 8. Progress log

- 2026-08-16：用户要求把上一阶段建议的 16 个通用 Capability 纳入一次授权后的持久授权模型。
- 2026-08-16：确认系统 UI、Android runtime permission、后台定位和静默录音不能被持久授权绕过；
  设计使用 `foreground_required` 作为后台调用的明确错误。
- 2026-08-16：开始治理、契约、runtime 与测试实现。
- 2026-08-16：完成 16 项 beta Capability 的 Registry 生成物、SDK/Mock Host、Android provider、
  首次/增量持久授权、系统 UI 前台 gate 与加密 receipt 接入。
- 2026-08-16：补齐进程重建后的 durable subscription runtime 重绑定、前台页面优先分发、
  后台前台服务恢复，以及删除、禁用、复审和声明丢失时对无障碍/原生订阅、录音与 runtime 的
  统一清理；同步更新审阅 UI 与开发者文档。
- 2026-08-16：`generate:check`、tooling/platform typecheck、平台 unit/e2e、Manifest 示例 lint、
  Android JVM、`compileDebugAndroidTestKotlin`、`assembleDebug` 和 Open WebUI Vitest 均通过。
  `plugin-tooling npm test` 仅保留既有 CLI browser smoke 的 `window.load` 超时失败；未配置 adb，
  API 26/35 真实系统 UI instrumentation 交由阻塞 CI。
