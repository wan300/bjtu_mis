# ExecPlan: 插件 Android 系统自动化 Capability

## 1. Purpose and user-visible outcome

为 Manifest v3 / contract_v1 插件增加五项受限 Android 系统能力。用户在插件首次申请能力或更新增量申请时完成一次高风险授权，并在系统设置中启用宿主无障碍服务后，插件可以订阅无障碍事件、读取脱敏节点、执行受约束动作、读取可见安装包信息以及打开受限系统设置页面。持久订阅可在插件页面关闭或 App 进程重启后恢复，用户仍可从插件管理页、持续通知或系统无障碍设置立即停止。

成功标准：

- 五项 Capability 由单一 Contract Registry 生成 Android、SDK、平台、schema 和文档制品，均为 `beta`、protocol v2、`runtimeFloor = 2`。
- 原生桥仍只向稳定本地 origin 的 main frame 注入；远程 frame、publisher 不匹配和未授权插件无法调用。
- 节点密码与敏感输入永久脱敏，opaque `nodeId` 最多存活 30 秒；动作幂等但不逐次弹窗。
- 插件删除、禁用、撤销授权、进入 `needsReview` 或失去声明时立即停止 runtime、订阅和节点句柄。
- 所有要求的 tooling、Android、平台、Manifest 和 schema 验证通过；需要真实无障碍服务的 API 26/35 场景若本机无设备，则明确交由阻塞 CI。

## 2. Repository context

- Capability 权威源：`plugin-tooling/contracts/capability-contracts.json`。
- Android runtime：`android/app/src/main/java/cn/edu/bjtu/mis/data/thirdparty/`。
- Android 装配：`android/app/src/main/java/cn/edu/bjtu/mis/di/AppContainer.kt`。
- 插件管理 UI：`android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/ThirdPartyServiceScreens.kt`。
- SDK：`plugin-tooling/packages/plugin-sdk/`；Mock Host：`plugin-tooling/packages/mock-host/`。
- 生成物：Android generated contracts、SDK generated contracts、平台 contract、Manifest/marketplace schema 和 API 文档必须由生成器更新并提交。
- 相关测试：`android/app/src/test/kotlin/cn/edu/bjtu/mis/data/thirdparty/`、`android/app/src/androidTest/`、`plugin-tooling/test/` 与各 package 测试。

## 3. Constraints and non-goals

- 保留 Manifest v3 / contract_v1、publisher subject + plugin ID 隔离、稳定本地 origin/main-frame/source-origin 精确匹配和远程 frame 无桥边界。
- 只实现以下五项，不增加通知、剪贴板、媒体投射、任意 Intent、任意 URI 或通用原生 HTTP。
- `settings.open` 只允许 `android.settings.*` action、无 data 或 `package:` data URI；不接受 extras 或显式组件。
- `packages.read` 不返回 APK 路径、私有数据或图标字节；发行前提为现有 GitHub APK，后续 Google Play 发布必须重新做政策审查。
- 不引入 Room migration；持久订阅和幂等回执使用现有 Android Keystore/AES-GCM 安全文件模式。
- 不记录输入文本、节点文本、密码值或手势轨迹；日志和回执只保留必要摘要。
- 不修改或回退用户现有的 `android/app/src/main/java/cn/edu/bjtu/mis/ui/BjtuMisApp.kt` 与 `web/index.html` 改动。

## 4. Constitution check

- 原则 I：首次授权、增量更新审阅、可撤销和明确系统状态保留；动作免逐次确认是 constitution 2.0.0 中仅限声明的 Android 自动化 Capability 的持久授权例外。
- 原则 IV：Contract Registry、生成物一致性、权限拒绝、服务不可用、脱敏、过期、限流、恢复、幂等、清理和 WebView 边界均需要阻塞测试。
- 原则 VI：本文件覆盖目标、里程碑、验证、失败处理和回滚，并持续更新。
- 原则 VII：不改变稳定本地 origin/main-frame 桥接、publisher 隔离、远程 frame 无桥、公网分域与只读校园代理；新增能力不构成通用 Android Intent 或通用 HTTP。

## 5. Proposed design

- Contract Registry 定义五项能力的请求、响应、事件、权限、错误、配额、超时与稳定性；生成器派生全部公开制品。
- `PluginAccessibilityService` 把系统回调交给进程内 gateway。gateway 按 publisher+plugin 隔离订阅、事件过滤、每订阅 60 events/s 限流、最多 16 个订阅，并只给当前前台 runtime 派发；无前台页面时交给最多四个后台 WebView runtime。
- 节点遍历使用严格全局计数，最多 4,096 节点、深度 64、文本字段 4 KiB。密码节点及敏感可编辑输入的文本和值始终返回脱敏值。节点句柄使用不可预测 opaque ID 并在 30 秒后失效。
- 节点、全局与手势动作要求 `idempotencyKey`，使用加密摘要回执去重；初次能力授权后不再逐次弹确认。动作按 Capability 配额在 runtime 强制限流。
- `PluginAutomationStore` 加密保存持久订阅；`PluginAutomationSupervisor` 在服务连接且插件仍安装、启用、授权、声明匹配且无需复审时恢复后台 runtime。前台页面拥有事件派发优先权，禁止前后台重复派发。
- 前台服务持续通知列出自动化正在运行，并提供打开管理页和“全部停止”操作。停止、撤销、删除、publisher 变化、能力丢失或复审都会清理订阅和节点句柄。
- package provider 使用 `PackageManager` 映射允许字段并计算签名 SHA-256；settings provider 构造最小隐式 Intent，所有输入在启动前校验。

## 6. Files and components to change

- `.specify/memory/constitution.md`：升级至 2.0.0，定义持久 Android 自动化的受限例外和 Sync Impact Report。
- `AGENTS.md`、`PLANS.md`、`.specify/templates/`：同步权限、测试、持久授权和回滚要求。
- `plugin-tooling/contracts/capability-contracts.json` 及生成物：五项 Capability 契约。
- `plugin-tooling/packages/plugin-sdk/`、`plugin-tooling/packages/mock-host/`：`android` SDK 命名空间、事件与 Mock Host。
- `android/app/src/main/AndroidManifest.xml`、`res/xml/`、`res/values/`：权限、AccessibilityService、前台服务类型与用户文本。
- `android/app/src/main/java/cn/edu/bjtu/mis/data/thirdparty/`：provider、gateway、加密 store、supervisor、registry 与 repository 清理。
- `android/app/src/main/java/cn/edu/bjtu/mis/di/AppContainer.kt`：依赖注入。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/ThirdPartyServiceScreens.kt`：高风险授权、服务状态、设置入口与撤销/停止控制。
- `docs/third-party-services.md`、`web/developers.html`：开发者语义、限制和示例。
- 对应 JVM、instrumentation、tooling、SDK 与 Mock Host 测试。

## 7. Milestones

### Milestone 1: Governance and contracts

- 可观察行为：constitution 2.0.0 和本 ExecPlan 明确例外边界；五项 contract 及所有生成物一致。
- 验证：`npm.cmd run generate:check`、schema 字节一致性、Manifest lint。
- 状态：completed；contract、生成物、constitution 2.0.0、模板和开发者文档已同步。

### Milestone 2: Android runtime and lifecycle

- 可观察行为：服务启用后可调用五项能力；持久订阅可恢复；前后台不重复派发；撤销和复审立即停止。
- 验证：Android JVM 测试、`:app:compileDebugAndroidTestKotlin`、API 26/35 instrumentation 或阻塞 CI。
- 状态：completed；provider、service、store、supervisor、配额、前台优先、停止全部、复审与删除清理已实现并通过 JVM/编译验证。

### Milestone 3: SDK, Mock Host and documentation

- 可观察行为：插件通过 `sdk.android.*` 使用全部接口和事件；Mock Host 可确定性模拟成功、拒绝、过期和事件派发。
- 验证：tooling typecheck、unit、pack check。
- 状态：completed；SDK `android` 命名空间、强类型事件、Mock Host 断开/配额场景和测试已完成。

### Milestone 4: Full verification and handoff

- 可观察行为：所有相关构建和测试通过，未包含 secrets、APK/AAB、缓存或无关改动。
- 验证：完整 Test Plan、`git diff --check`、`git status --short`。
- 状态：completed with CI follow-up；本机可执行验证已完成，API 26/35 真实服务场景交由阻塞 CI。

## 8. Step-by-step implementation plan

1. 核对 worktree 与生成器，保护用户现有改动。
2. 完成 contract、生成物、SDK 与 Mock Host，确认 protocol v2、runtime floor 和 beta 元数据。
3. 完成 AccessibilityService/gateway 的严格节点预算、脱敏、句柄过期、订阅与动作运行时配额。
4. 完成加密持久化、四 runtime 上限、前台优先、持续通知停止入口以及所有撤销/复审/删除清理。
5. 完成 package/settings provider 的最小数据与 Intent 校验。
6. 更新首次授权和服务管理 UI、constitution、代理指南、模板与开发者文档。
7. 添加 tooling、SDK、Mock Host、Android JVM 与 instrumentation 测试。
8. 运行完整验证，修复本次变更引入的问题；记录只能交由设备/CI 的验证。
9. 检查差异和生成物，更新本计划的进度、决策与完成清单。

## 9. Validation plan

- Tooling：`Set-Location plugin-tooling; npm.cmd run generate:check; npm.cmd run typecheck; npm.cmd test; npm.cmd run pack:check`。
- Android：`Set-Location android; .\gradlew.bat test; .\gradlew.bat :app:compileDebugAndroidTestKotlin; .\gradlew.bat assembleDebug`。
- Platform：`Set-Location web\platform; npm.cmd run typecheck; npm.cmd test; npm.cmd run test:integration; npm.cmd run test:e2e`。
- Manifest/schema：`node tools/third-party-service-lint.cjs` 覆盖模板与示例；`docs/` 和 `web/assets/schemas/` 镜像必须字节一致。
- 设备：API 26/35 验证真实节点、事件、动作、后台 runtime、前台通知、main-frame/source-origin 边界和撤销。无可用设备时记录为阻塞 CI 项，不以 JVM fake 代替结论。
- 最终：`git diff --check` 与 `git status --short`。

## 10. Decision log

- Decision：五项能力均为 protocol v2、runtime floor 2、beta。
  - Consequence：旧 runtime 不能声明或调用这些能力。
- Decision：动作使用首次/增量 capability 授权，不逐次确认。
  - Consequence：constitution 升级为 2.0.0，并要求随时撤销、敏感数据最小化、幂等和审计摘要。
- Decision：使用 `QUERY_ALL_PACKAGES`，发行范围限定 GitHub APK。
  - Consequence：任何 Google Play 上架工作必须重新审查政策和数据范围。
- Decision：不做 Room migration，自动化状态使用 publisher+plugin 隔离的加密文件。
  - Consequence：旧版回滚不会识别或执行新状态；删除和撤销仍由当前版本清理。

## 11. Risks and rollback plan

- 风险：无障碍能力可观察和控制其他 App。缓解：显式高风险授权、系统服务二次启用、字段脱敏、配额、短期句柄、发布者隔离、持续通知和即时撤销。
- 风险：后台 runtime 重复派发或失控。缓解：前台优先单一归属、全局四 runtime、停止全部、生命周期清理和测试。
- 风险：包清单扩大隐私面。缓解：只返回契约字段，不返回路径/数据/图标；发布渠道约束写入治理文档。
- 风险：应用升级或写入中断损坏订阅。缓解：加密原子替换和可恢复读取；恢复前重新验证授权、声明、publisher 和 review 状态。
- 回滚：移除 Manifest service/permissions 与五项 capability provider，重新生成契约制品；旧版忽略自动化加密文件，用户可在系统无障碍设置立即关闭服务。回滚不需要 Room migration。
- 数据清理：插件删除或用户撤销时删除对应 publisher+plugin 自动化记录；失败必须保留可重试状态并停止执行。

## 12. Progress log

- 2026-08-12：记录初始 worktree；确认用户已有 `BjtuMisApp.kt` 与 `web/index.html` 改动不在任务范围。
- 2026-08-12：完成五项 Contract Registry 初稿和确定性生成物；Android provider、service、store、supervisor、registry、repository、Manifest 与授权 UI 已有可编译初稿。
- 2026-08-12：`:app:compileDebugKotlin` 曾通过；完整测试、运行时配额、Mock Host、治理同步和设备验证仍待完成。
- 2026-08-12：补齐 publisher+plugin node handle 隔离、严格 4,096 节点预算、30 秒过期、敏感输入脱敏、事件/动作/Settings 配额、前台优先 runtime sink 和通知“全部停止”。
- 2026-08-12：Android `test`、`:app:compileDebugAndroidTestKotlin` 与 `assembleDebug` 通过。首次合并执行被工具超时中断并留下 Gradle 文件锁，停止残留 daemon 后分别重跑成功。
- 2026-08-12：tooling `generate:check`、typecheck、28 项 contract/SDK 测试与 `pack:check` 通过；完整 `npm test` 的 35 项中 34 项通过，唯一失败为 CLI Chrome smoke 的本机浏览器可执行探测，显式 Chrome 路径复试仍在 `--version` 探测失败。
- 2026-08-12：platform typecheck、17 项 unit、6 项 e2e 通过；2 项 PostgreSQL integration 因未设置 `TEST_DATABASE_URL` 按设计跳过。
- 2026-08-12：四组 Manifest lint 通过（两个 optional Capability 提示为预期 warning），两组 docs/web schema 镜像 SHA-256 分别一致，`git diff --check` 通过。
- 2026-08-12：本机未连接 API 26/35 设备，真实 AccessibilityService 节点/事件/动作、后台 WebView 和通知场景未运行，保留为阻塞 CI 项。

## 13. Completion checklist

- [x] 记录目标、范围、设计、验证、失败处理和回滚。
- [x] 保护用户现有 worktree 改动。
- [x] constitution 2.0.0、代理指南、模板与开发者文档已同步。
- [x] 五项 contract、SDK、Mock Host 与生成物完整且一致。
- [x] Android runtime、持久化、前后台优先、撤销和停止全部行为完成。
- [x] 必要 JVM、tooling、SDK、Mock Host 测试及 instrumentation 编译完成；真实设备执行已明确交由 CI。
- [x] 完整的本机可执行验证已运行并记录环境限制。
- [x] 最终 diff 不含 secrets、本机配置、构建产物、APK/AAB 或无关改动；用户原有两处改动保持原样。
