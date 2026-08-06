# ExecPlan: 插件运行时 P0 + P1 契约化改造

> **状态：已完成（2026-07-30，含内部 Beta 评审修复）。** P0 + P1 主体于
> 2026-07-29 完成；2026-07-30 已继续收口生命周期事件、统一超时/错误、CLI 与
> 平台校验、Cache 资源生命周期、WebStorage 边界和 SDK 类型，并完成本机可执行
> 验证。API 35 WebView instrumentation 已在本机 AVD 运行；真实 PostgreSQL 与
> API 26（以及完整 API 26/35 矩阵）继续由阻塞 CI 执行。
> 本计划是当前插件实现的规范执行计划，并取代
> `docs/plugin-platform-manifest-v3-p0a-execplan.md`。旧 P0-A 计划仅用于历史追踪与
> 无桥数据救援；冲突时以 constitution 1.2.0、本计划、Capability Contract Registry
> 和生成的 schema 为准。
>
> **后续修订（2026-08-05）：** 本计划中“仅 ArrayBuffer、缺少
> `WEB_MESSAGE_ARRAY_BUFFER` 即关闭二进制 capability、禁止 Base64 fallback”的条款
> 已由 `docs/plugin-binary-transport-compat-execplan.md` 取代。稳定 self origin、main
> frame/source-origin、配额、超时、摘要和临时文件安全边界继续有效。

## 1. Purpose and user-visible outcome

- 用户场景：插件作者使用 TypeScript SDK 与 CLI 编写普通 Web 业务代码；用户安装后只需审阅清晰的 Capability 与 origin 增量。
- 当前问题：Manifest 暴露过多宿主实现字段；桥、路由、文档、Android 校验和平台校验由多套手写定义维护；P0-A runtime 是大型 Compose/WebView 单体；缺少隔离原生网络、KV 并发语义、Blob/Cache 资源以及写命令幂等。
- 完成后的可观察行为：
  - 最小 `bjtu-plugin.json` 可安装，`bridge_origins` 等宿主不变量不再由作者声明。
  - SDK 通过 protocol v2 调用 Capability，optional Capability 首次安装默认关闭，远程 frame 永远无桥。
  - P0-A v3 只能进入无桥、无网络救援；同 publisher subject + plugin ID 可受控原位升级并保留配置、KV、Blob 命名空间与稳定 origin。
  - 插件可使用隔离网络、KV2、Blob、Cache 与需逐次确认的 Command Capability。
  - 平台 `/api/v3` 发布 contract_v1；`/api/v2` 冻结为旧目录只读接口。
- 成功标准：计划列出的 tooling、platform、Android JVM/编译、schema/lint 和安全测试通过；依赖设备的 API 26/35 场景在 CI 阻塞执行。

## 2. Repository context

- Android 工程：`android/`；插件数据层位于 `android/app/src/main/java/cn/edu/bjtu/mis/data/thirdparty/`。
- 实施前 WebView/Compose 单体：`android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/ThirdPartyServiceScreens.kt`。
- 实施前 Room 版本：11；迁移入口为 `android/app/src/main/java/cn/edu/bjtu/mis/data/db/AppDatabase.kt`。
- 实施前平台公开目录为 `/api/v2`，worker 读取 `bjtu-service.json`；本轮在 `web/platform/` 增加 `/api/v3` 与 contract-only worker。
- 实施前 schema/lint：`docs/third-party-service-manifest.schema.json`、`web/assets/schemas/third-party-service-manifest.schema.json` 与 `tools/third-party-service-lint.cjs`。
- 新工具工作区：`plugin-tooling/`，包含唯一 Contract Registry、生成器、SDK、CLI、create 包与 Mock Host。
- 2026-07-29 开始实施时 `git status --short` 无输出，工作树干净。

## 3. Constraints and non-goals

- 必须遵守 constitution 1.2.0，尤其是稳定 publisher 身份、main-frame/source-origin、宿主固定 self bridge、publisher+plugin 数据隔离与校园会话边界。
- 不恢复 v1/v2 或 P0-A runtime；旧格式只可无桥、无网络救援。
- 不恢复明文凭据、宿主 Cookie、宿主认证器或通用 `app.http_request`。
- 不直接维护 `android/app/src/main/assets/public/`；不提交 dev 配置、secret、APK/AAB、`node_modules`、数据库卷或构建缓存。
- 本轮不实现 SSE/WebSocket 公网代理、下载队列、SAF 文件能力、OAuth、任意 Android Intent、签名包格式、设备 Capability、声明式扩展点或正式诊断面板。
- 本轮构建并测试 npm 包，但不执行 npm publish。

## 4. Proposed design

### 4.1 Contract and tooling

- `plugin-tooling/contracts/capability-contracts.json` 是 Capability 的唯一权威定义，包含 ID/版本、稳定性、方法、JSON Schema、权限、逐次确认、幂等、配额、超时、错误和 Android/WebView 支持范围。
- 确定性生成器提交 Manifest/marketplace schema、TypeScript SDK/类型/Mock、Kotlin descriptor/router/validator 与开发者 API 文档；`generate:check` 在临时目录重生成并逐字节比较。
- workspace 交付 `@bjtu-mis/plugin-sdk`、`@bjtu-mis/plugin-cli` 和 `create-bjtu-plugin`。CLI 提供 `create/dev/lint/test/pack/doctor/inspect/migrate`；pack 固定排序与时间戳并拒绝 `bjtu-plugin.dev.json`。

### 4.2 Manifest and platform

- 发布包根目录只使用 `bjtu-plugin.json`；Manifest 保留 `schema_version`、身份、版本、entrypoint/icon、Capability、分域 origin、可选 configuration 与条件 data schema/migration。
- `bjtu-marketplace.json` 独立保存描述、作者、分类、标签、许可证和截图；大厅投稿必需，GitHub 直链可缺省。
- `/api/v3` 保存并返回 contract profile、派生 runtime floor、Capability 与独立 marketplace 元数据；新 worker 只发布 `bjtu-plugin.json`。
- `/api/v2` 的 GET 目录/详情/制品保持只读；投稿、更新、transfer 与管理写入口冻结。

### 4.3 Android persistence and upgrade

- Room 11→12 将记录分类为 `legacy_v1_v2`、`legacy_v3_p0a` 或 `contract_v1`，保存 granted capabilities、contract profile、runtime floor 与 Blob/Cache/Command 元数据。
- 迁移不删除包、配置、KV 或资源。P0-A 原位升级必须同时匹配 publisher subject、plugin ID 和数据 schema 兼容条件；失败保持救援状态。
- 更新保留上一版本包、授权、影子 KV/Blob 索引与稳定 origin；回滚到 P0-A 只能回到救援状态。

### 4.4 Runtime boundaries

- 宿主拆分为 `PluginRuntimeHost`、`BridgeTransport`、`CapabilityRegistry/Provider`、`PluginWebViewPolicy`、`PluginLifecycleDispatcher`、`PluginResourceServer`、`PluginNavigationController` 和脱敏 `PluginDiagnostics`。
- protocol v2 使用 camelCase、独立 `capability`/`method`、统一错误、取消与订阅。SDK 不公开 `window.BjtuService` 或底层 transport。
- 只有稳定本地 origin 的 main frame 获得桥；远程 frame 无桥。~~缺少 `WEB_MESSAGE_ARRAY_BUFFER` 时 required 二进制 Capability fail closed，optional 标记 unavailable。~~ 此 ArrayBuffer-only 条款已由 `docs/plugin-binary-transport-compat-execplan.md` 取代。
- debug source set 可在用户显式开启后把稳定 origin 的 HTTP/HMR WebSocket 转发到 adb reverse loopback；release source set 不含入口。

### 4.5 Capability providers

- 稳定 Capability 覆盖 lifecycle、configuration、远程 frame、外部导航、现有身份/教务/邮件读取与只读 `campus.request@1`。校园读取统一返回 `{ data, meta }`。
- `network.request@1` 使用无 Cookie、无宿主认证器的独立 OkHttp；每次 DNS 与重定向执行 SSRF 校验，拒绝传输层 Header，限制方法、超时、重定向、并发、内联响应与大小。
- `storage.kv@2` 增加 batch、revision/CAS、声明式原子事务、watch 与 Blob-handle 导入导出，并可读取旧 KV 文件。
- Blob/Cache 使用 publisher+plugin 隔离、AES-GCM 分块与原子索引；Blob 不可变且内容寻址，Cache 为 LRU。资源经稳定 origin 的 `/__bjtu/resources/<handle>` 提供 GET/HEAD/Range。
- 所有写操作是逐次确认的 Command Capability，必须提供 idempotency key。加密回执只保存摘要、结果与时间，保留 7 天且每插件最多 1024 条。

## 5. Public contracts

- Manifest 文件：`bjtu-plugin.json`。
- marketplace 文件：`bjtu-marketplace.json`。
- 本地开发文件：`bjtu-plugin.dev.json`，禁止进入发布包。
- protocol v2 请求：`{ protocolVersion, requestId, capability, method, params }`。
- protocol v2 响应：成功为 `{ protocolVersion, requestId, ok: true, result }`；失败为 `{ protocolVersion, requestId, ok: false, error }`。
- 统一错误至少包括 `permission_denied`、`capability_unavailable`、`invalid_request`、`origin_denied`、`network_timeout`、`http_error`、`quota_exceeded`、`resource_too_large`、`migration_failed`、`user_cancelled` 和 `idempotency_conflict`。
- 网络默认 15 秒、上限 60 秒、最多 5 次重定向；每插件并发 4、每 origin 并发 2；JSON/文本内联上限 1 MiB。
- KV 配额 10 MiB/单项 256 KiB/1024 keys；Blob 每插件 256 MiB/单项 64 MiB；Cache 每插件 512 MiB/全局 1 GiB/单项 250 MiB，并保留设备安全剩余空间。

## 6. Files and components to change

- `.specify/memory/constitution.md`、`AGENTS.md`、`PLANS.md` 与 Spec Kit 模板：更新 contract_v1 治理。
- `plugin-tooling/`：registry、生成器、packages、模板、测试和 lockfile。
- `docs/`、`tools/`、`third-party-plugins/`、`web/developers.html`：生成 schema、lint、开发文档和示例。
- `web/platform/src/`、`web/platform/migrations/`、`web/platform/test/`：API v3、worker、持久化和冻结 v2。
- `android/app/src/main/java/cn/edu/bjtu/mis/data/thirdparty/`：Manifest、Capability providers、network、KV2、Blob/Cache、命令回执、安装/更新。
- `android/app/src/main/java/cn/edu/bjtu/mis/data/db/` 与 `android/app/schemas/`：Room 12 和迁移。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/ThirdPartyServiceScreens.kt` 及新的 runtime 包：宿主装配和模块化 WebView runtime。
- Android JVM/instrumentation、tooling 与 platform 测试；`.github/workflows/plugin-platform-v3.yml`。

## 7. Milestones

### Milestone 1: 治理与单源契约

- 预期行为：constitution 1.2.0 与本计划生效；registry 可确定性生成所有公共契约。
- 验证：`npm run generate:check`、schema 镜像字节比较、生成器测试。
- 状态：complete

### Milestone 2: SDK、CLI、Mock 与模板

- 预期行为：默认模板可创建、开发、lint、测试和确定性打包；SDK 覆盖 handshake、取消、错误、事件和二进制 feature gate。
- 验证：`plugin-tooling` 的 typecheck、unit 与 `pack:check`。
- 状态：complete

### Milestone 3: 平台 API v3

- 预期行为：新目录只接收 contract_v1 与独立 marketplace；v2 只读，publisher 与 digest 约束不降级。
- 验证：平台 typecheck、unit、integration、e2e。
- 状态：complete

### Milestone 4: Android contract runtime 与 Room 12

- 预期行为：旧格式进入救援；受控原位升级保留数据；生成路由驱动拆分后的 runtime。
- 验证：Android JVM、Room migration、WebView instrumentation 编译。
- 状态：complete

### Milestone 5: P1 providers

- 预期行为：network、KV2、Blob/Cache、资源 Range 与 Command 幂等符合配额和安全边界。
- 验证：对应 JVM 与 instrumentation 测试。
- 状态：complete

### Milestone 6: 文档、CI 与全量验收

- 预期行为：文档、模板、示例和 CI 与生成契约一致；所有本机可执行验证通过。
- 验证：第 9 节全部命令与最终 diff/secret/产物检查。
- 状态：complete

## 8. Step-by-step implementation plan

1. 检查工作树；升级 constitution；创建并持续维护本 ExecPlan。
2. 创建 Contract Registry 与确定性生成器，提交所有生成物和漂移测试。
3. 实现 SDK、CLI、create 包、Mock Host、默认 Vite 模板及 tooling 测试。
4. 修改平台 schema、SQL、repository、routes 与 worker，新增 `/api/v3` 并冻结 `/api/v2` 写入口。
5. 修改 Android Manifest/model/validator、Room entity/DAO/migration/repository 与受控升级。
6. 抽出 WebView runtime 组件并接入 protocol v2、Capability 授权、取消/事件与 ArrayBuffer feature gate。
7. 实现 network、KV2、Blob/Cache/资源服务器与 Command receipt providers。
8. 为现有领域 API 增加生成契约与 provider 映射，统一读取结果 meta。
9. 同步开发文档、示例、lint、CI 和旧格式迁移 fixture。
10. 运行验证，修复范围内失败，更新进度/决策记录并检查最终工作树。

## 9. Validation plan

- Tooling：
  - `Set-Location plugin-tooling; npm ci`
  - `npm run generate:check`
  - `npm run typecheck`
  - `npm test`
  - `npm run pack:check`
- Platform：
  - `Set-Location web/platform; npm run typecheck`
  - `npm test`
  - `npm run test:integration`
  - `npm run test:e2e`
- Android：
  - `Set-Location android; .\gradlew.bat test`
  - `.\gradlew.bat :app:compileDebugAndroidTestKotlin`
  - `.\gradlew.bat :app:compileReleaseKotlin`
  - `.\gradlew.bat assembleDebug`
- Schema/WebView：
  - lint 新模板、示例与迁移 fixture。
  - CI 在 API 26/35 执行 main-frame/source-origin、远程 frame、资源 Range、ArrayBuffer gate 与 debug/release 隔离 instrumentation。
- 完成前：`git diff --check`、`git status --short`、生成产物/secret/dev 配置检查。

## 10. Acceptance scenarios

- 最小 Manifest 可安装，optional Capability 默认关闭。
- P0-A v3 只能救援；同 subject + ID 的 contract_v1 原位升级保留配置、KV、Blob 与 stable origin。
- 远程 frame 永无桥；公网请求不携带宿主会话并阻止 SSRF。
- `AbortSignal` 能取消请求，进度事件可订阅；大响应返回 handle。
- 资源在离线状态支持 GET/HEAD/Range；原 ArrayBuffer required/optional fail-closed 验收已由兼容传输计划取代。
- KV 竞争通过 revision/CAS/transaction 不丢更新。
- 相同 idempotency key 与相同摘要返回原回执；不同摘要返回 `idempotency_conflict`。
- 任何生成物手改漂移阻塞 CI。

## 11. Progress log

- 2026-07-29：重新检查 `git status --short`，工作树干净。
- 2026-07-29：确认 constitution 1.1.0 的作者声明 `bridge_origins` 与宿主固定不变量冲突；开始升级 constitution 1.2.0 并创建本 ExecPlan。
- 2026-07-29：完成 Contract Registry、确定性生成器、SDK/CLI/Mock、Vanilla Vite 模板及 debug-only Android HMR transport；`generate:check`、tooling typecheck 与测试通过。
- 2026-07-29：完成平台 migration 003、`/api/v3`、P0-A `/api/v2` 写冻结与 contract-only worker，并完成平台 typecheck/unit/e2e。
- 2026-07-29：完成 Room 12、旧 runtime 分类、受控原位升级、protocol v2 模块化 WebView runtime、Capability providers、network、KV2、Blob/Cache 与 Command receipt。
- 2026-07-29：受控 P0-A 升级测试证明配置、KV、Blob 和 stable origin 保留；回滚只恢复救援状态。
- 2026-07-29：Compose 授权入口已改为 Capability 审阅：required 必须选中，首次安装的 optional 默认关闭，更新只保留用户此前明确授予且仍声明的 optional；删除旧 `grantService(permission…)` 与独立 permission registry。细粒度 campus permission 仅保留为 Contract Registry 派生的内部策略数据。
- 2026-07-29：二进制桥输入改为有并发/大小/安全剩余空间限制的 app-private 临时文件流，再导入分块加密资源存储；不再把最高 250 MiB 的 payload 聚合到 JVM 堆。完成 `campus.request@1` ID/授权、默认端口 origin 规范化及 IPv6 ULA/CGNAT SSRF 收口。
- 2026-07-29：完成 tooling 验收：`npm ci`、`generate:check`、typecheck、无产物 Vanilla TypeScript + Vite 模板构建、17 项测试和 `pack:check` 均通过，插件归档两次生成的 SHA-256 一致。
- 2026-07-29：首次单独检查模板时因 tooling workspace 未安装 Vite 而失败；补充可复现的 Vite 开发依赖和 `template:check` 后，更新 lockfile、重跑 `npm ci` 并通过。SDK 主入口生成的 `.d.ts` 现由测试证明不暴露 host/migration transport，Mock 子入口覆盖设备、主题、生命周期和 ArrayBuffer feature gate。
- 2026-07-29：Vite/esbuild 与确定性 pack 的派生子进程在受限 Windows 沙箱中曾返回 `spawn EPERM`；使用相同源码和命令在获批的非沙箱执行环境重跑后通过。这是执行环境限制，不是测试断言或实现失败。
- 2026-07-29：完成平台验收：typecheck、17 项单测和 6 项 e2e 全部通过；integration 命令成功执行，但本机未提供 `TEST_DATABASE_URL`，2 项真实 PostgreSQL 用例按设计跳过。CI 已配置 PostgreSQL 16 与该环境变量作为阻塞验证。
- 2026-07-29：完成 Android 验收：定向安全测试以及 `test`、`compileDebugAndroidTestKotlin`、`compileReleaseKotlin`、`assembleDebug` 均通过。构建仅报告既有 WebView file URL 设置的弃用警告。
- 2026-07-29：模板、示例和 CLI 模板 lint 通过；Manifest/marketplace 公共 schema 字节一致；release 合并产物未包含 debug HMR receiver/transport；`git diff --check` 通过，仅有工作区 CRLF 提示。
- 2026-07-29：完成 secret、构建产物、本机配置和旧接口残留审计。旧名称只保留在拒绝逻辑、迁移 fixture、冻结 v2 映射及明确标记的历史文档中；活动规格已同步为 contract_v1。
- 2026-07-29：当日未启动 API 26/35 设备，因此不伪造 instrumentation 运行结果；工作流矩阵已在 API 26 与 35 上配置阻塞执行。
- 2026-07-30：完成内部 Beta 评审修复。Registry 现统一生成事件、deadline、错误、
  SDK 类型、Android 路由校验和平台包体限制；Android 与 Mock Host 不再维护平行
  生命周期协议，返回键通过可确认事件只回退一次。
- 2026-07-30：`bjtu test` 已在本机 headless Edge 中完成真实页面加载与 protocol v2
  handshake；CLI 与官方 lint 的 marketplace/configuration fixture 一致，发布包
  25 MiB、解压 50 MiB、1000 文件限制来自同一 Registry。
- 2026-07-30：完成全量复验：tooling `generate:check`、typecheck、23 项测试和
  `pack:check` 通过；platform typecheck、17 项单测和 6 项 e2e 通过，integration
  命令成功且因本机未设置 `TEST_DATABASE_URL` 跳过 2 项真实 PostgreSQL 用例；
  Android `test`、instrumentation 编译、release Kotlin 编译和 `assembleDebug`
  通过。四个源码 fixture lint 与两组公共 schema 字节镜像通过。
- 2026-07-30：最终 `git diff --check` 通过；活动 contract_v1 路径未发现旧桥、
  凭据能力或通用宿主 HTTP 接口，旧字段只保留在拒绝、迁移和冻结 v2 兼容路径。
- 2026-07-30：复核 Android WebView 存储边界后，不再把
  `domStorageEnabled=false` 当作 IndexedDB 的充分证明。contract_v1 增加
  document-start 不可重配 guard、`worker-src 'none'` 与桥 fail-closed marker；
  API 26/35 instrumentation 直接执行插件 JavaScript，验证 WebStorage、IndexedDB、
  Cache Storage、Cookie、Worker/Service Worker 和浏览器文件存储均不可用。
- 2026-07-30：启动本机 API 35 AVD 执行
  `:app:connectedDebugAndroidTest -PtargetAbis=x86_64`，13 项 instrumentation
  全部通过且无跳过，随后正常关闭模拟器。API 26 仍由阻塞 CI 覆盖。

## 12. Decision log

- Decision：保留 `schema_version: 3`，以 contract profile 区分 P0-A 与 contract_v1。
  - Context：避免无意义提升文件格式大版本，同时需要硬停旧 runtime。
  - Chosen approach：紧凑字段集合与 `contract_v1` 数据库状态共同判定；P0-A 不再执行。
- Decision：桥 origin 不进入 Manifest。
  - Context：self bridge 是安全不变量，不应成为作者可配置输入。
  - Chosen approach：宿主只向稳定本地 main frame 注入并校验精确 source origin。
- Decision：Contract Registry 是唯一公共契约源。
  - Context：当前 TS/Kotlin/schema/docs/route 多处手写会漂移。
  - Chosen approach：提交确定性生成物，CI 重生成并逐字节比较。
- Decision：浏览器 fetch 与隔离 `network.request@1` 并存。
  - Context：普通 Web 开发体验与大响应/进度/受控原生能力用途不同。
  - Chosen approach：两者都受 manifest connect origin；原生请求额外执行 DNS/redirect/headers/Cookie 边界。

## 13. Risks and rollback plan

- Room 12 不提供 downgrade migration；客户端回滚必须以前向 hotfix 完成。
- 平台先部署 additive migration 与 `/api/v3`，再发布新客户端；旧客户端继续读取冻结 `/api/v2`，不会看到新插件。
- Android 更新失败恢复上一 contract_v1 包、授权、KV 与 Blob 索引；若上一包是 P0-A，则只恢复救援状态。
- 资源索引或删除失败保留加密数据与 tombstone，后续重试；不得报告虚假成功。
- ~~WebView/ArrayBuffer 能力缺失时 fail closed，不使用 Base64 或不安全桥 fallback。~~ 已被后续兼容传输计划取代；当前只对两个核心安全 feature fail closed，Base64URL v1 是受约束的协商通道。
- 如生成器或 registry 回滚，必须连同全部生成物回滚；禁止只回滚单一语言输出。

## 14. Completion checklist

- [x] 工作树初检完成。
- [x] constitution 1.2.0 与安全 ExecPlan 已建立。
- [x] Contract Registry、生成器、SDK/CLI/Mock 与模板完成。
- [x] 平台 `/api/v3` 与 v2 冻结完成。
- [x] Room 12、contract_v1 runtime 与受控原位升级完成。
- [x] network、KV2、Blob/Cache、Command providers 完成。
- [x] 文档、示例、lint 与 CI 同步完成。
- [x] 全部本机可执行验证完成；API 35 instrumentation 本机通过，真实 PostgreSQL
  与 API 26 验证明确交由阻塞 CI。
- [x] 最终 `git diff`、secret、构建产物与 dev 配置检查完成。

## 15. 2026-07-30 internal Beta review remediation

### Scope

1. 由 Contract Registry 明确定义生命周期、KV watch 和网络进度事件；Android、SDK
   与 Mock Host 只使用生成事件名和数据类型。返回键事件带确认回执，宿主仅在插件
   未处理或确认超时后执行一次 WebView history/关闭回退。
2. Android 对所有 Capability route 执行生成 descriptor 的默认/最大超时；SDK
   同样设置本地看门狗并在超时后发送 cancel。新增统一 `request_timeout`，保留网络
   专用 `network_timeout`；存储配额和大小异常不得降级为
   `capability_unavailable`。
3. `bjtu test` 必须在真实无头 Chromium/Chrome/Edge 中加载发布入口，并通过私有
   protocol v2 Mock bridge 完成 handshake/ready 与运行时错误检查。CLI 补齐
   marketplace/configuration 校验和平台 25 MiB/50 MiB/1000 files 限制，并增加
   CLI/官方 lint 一致性 fixture。
4. `cache.resource@1` 增加按 handle 原地 promote 与 deleteHandle，网络产生的临时
   Cache 资源无需复制即可命名、pin 或删除。
5. `contract_v1` WebView 关闭 DOM Storage，并以 document-start 不可重配 guard、
   CSP worker 禁令和桥 marker 禁用 IndexedDB 及其他浏览器持久化；legacy rescue
   WebView 继续启用旧存储以允许用户读取数据。受管 KV/Blob/Cache 是本契约唯一持久化路径；
   适合大量可查询元数据的结构化存储 Capability 作为后续独立设计，不在本轮仓促
   引入。
6. SDK façade 使用生成 request/response/event 类型，消除可建模 route 的
   `Promise<unknown>`；补充校园顶层数据模型。Manifest/runtime origin 均移除默认
   `:443`，Blob/Cache/Bridge/ResourceServer 一致支持零字节资源。

### Rollback

- 事件、方法与错误扩展均发生在尚未宣布 stable 的 internal Beta；若需回滚，必须
  同时回滚 Registry 与全部生成物，禁止只回滚 SDK 或 Android 单侧。
- Cache promote 只原子修改加密索引，不复制或删除源资源；失败保持原 handle/key。
- 禁用 DOM Storage 不删除既有 origin 数据；回滚只需恢复 WebView 开关。legacy
  rescue 路径不变。

### Validation

- Tooling：生成漂移、SDK timeout/event/back ack、Mock schema、CLI 浏览器 smoke、
  lint parity、包体边界与 deterministic pack；本机全部通过。
- Android：生命周期 event ack、单次 back fallback、route timeout、typed storage
  errors、Cache promote/deleteHandle、零字节 GET/HEAD、默认端口和 DOM Storage
  policy；JVM、instrumentation 编译、release 编译与 debug 打包全部通过，API 35
  AVD 的 13 项 instrumentation 全部通过。
- Platform：共享限制与 marketplace fixture、typecheck/unit/integration/e2e；除本机
  未配置数据库而按设计跳过的 2 项 PostgreSQL integration 外全部通过，真实数据库
  用例由 CI 的 PostgreSQL 16 service 阻塞执行。

## 16. 2026-07-30 final review follow-up

### Scope

1. Treat both `handled: true` and `handled: false` as terminal SDK acknowledgements.
   The host fallback waits for 150 ms only when no acknowledgement arrives.
2. Exercise the protocol through a real Android WebView for handled, unhandled,
   and missing-acknowledgement timeout paths.
3. Correct the Android README to document Room 12 and `MIGRATION_11_12`.

### Rollback

- The acknowledgement change is isolated to pending lifecycle-event completion.
  Reverting it restores the old latency but does not alter persisted data or the
  bridge trust boundary.
- The Room change is documentation-only; the existing forward-only Room 12
  migration and rescue behavior remain unchanged.

### Validation

- Android JVM tests, instrumentation compilation, and release Kotlin compilation
  passed locally.
- The API 35 emulator ran all 8 tests in
  `ThirdPartyWebViewV3InstrumentationTest`, including handled, unhandled, and
  missing-acknowledgement timeout paths; all passed without skips. API 26 remains
  part of the blocking CI matrix.
- Re-run `git diff --check` and the repository secret/artifact audit before
  publication.
