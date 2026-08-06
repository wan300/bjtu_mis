# ExecPlan: 插件二进制兼容传输与 WebView 诊断

> **状态：实现、代码评审及本机分层验证完成（2026-08-06）；API 26/35 设备矩阵由阻塞 CI 执行。** 本计划取代
> `docs/plugin-runtime-contract-p0-p1-execplan.md` 中所有“二进制仅允许
> ArrayBuffer、缺少 `WEB_MESSAGE_ARRAY_BUFFER` 即关闭 Blob/Cache capability”
> 的活动约束；其余 Manifest v3 / contract_v1 零信任边界继续有效。

## 1. Purpose and user-visible outcome

- 用户场景：使用旧厂商 WebView 的用户仍需运行 contract_v1 插件，并允许插件把少量
  JavaScript 生成的数据写入受管 Blob/Cache。
- 当前问题：`storage.blob@1` 与 `cache.resource@1` 被全局绑定到
  `WEB_MESSAGE_ARRAY_BUFFER`，华为等旧 WebView 会被错误判定为 capability 不可用。
- 完成后的行为：支持 ArrayBuffer 的 WebView 继续走原通道；其余满足安全桥基础特性的
  WebView 使用 48 KiB Base64URL 分片。导入预检和独立诊断页显示 provider 与模式。
- 成功标准：只有缺少 `DOCUMENT_START_SCRIPT` 或 `WEB_MESSAGE_LISTENER` 才禁止运行；
  两种传输均验证 origin、main frame、顺序、大小、数量、SHA-256、配额、超时与清理。

## 2. Repository context

- 权威契约：`plugin-tooling/contracts/capability-contracts.json`；生成器位于
  `plugin-tooling/scripts/generate.mjs`。
- Android runtime：`PluginWebViewPolicy.kt`、`PluginRuntimeHost.kt`、
  `BridgeTransport.kt`、`ThirdPartyServiceApiRegistry.kt`。
- SDK/Mock：`plugin-tooling/packages/plugin-sdk/src/`；Compose 入口集中在
  `ThirdPartyServiceScreens.kt` 与 `BjtuMisApp.kt`。
- 网络大响应已经在原生侧流式写为 resource handle，`cache.promote` 不需要改数据流。
- 初始 worktree 有用户改动：`BjtuMisApp.kt`、`OverviewScreen.kt` 和
  `docs/apple-ui-refactor-execplan.md`；本任务不得回退它们。

## 3. Constraints and non-goals

- 保持 Manifest v3、contract_v1、protocol v2、runtime floor 2、稳定本地 self origin、
  main-frame/source-origin 精确校验、远程 frame 无桥及校园只读代理边界。
- 不修改 Room、Manifest schema、publisher identity、插件持久化格式或 Open WebUI。
- 不新增依赖，不发布 npm 包，不部署平台；只准备一致的 0.2.0 工具链制品。
- 图片下载不得绕经 JavaScript/Base64；Base64URL 仅用于插件生成的少量上传数据。
- 旧 SDK 的非二进制调用继续可用；新宿主拒绝旧无 transport/SHA-256 的二进制写入。

## 4. Proposed design

- 握手返回宿主支持的 `binaryTransports` 与可选 `preferredBinaryTransport`。现代 WebView
  返回 `arraybuffer`、`base64url-chunks-v1` 并首选前者；兼容 WebView 只返回后者。
- 私有声明为 `{ transport, size, chunks, sha256 }`。ArrayBuffer 保持 256 KiB；
  Base64URL 使用 RFC 4648 URL-safe 无填充编码、48 KiB 原始分片和逐片 ACK。
- Android 只保留单片字符串/字节，边解码边更新摘要并写临时文件；成功后以 InputStream
  交给现有加密 resource store。单插件最多四个暂存请求，deadline 使用 capability 的
  60 秒上限，并继续保留 64 MiB 磁盘安全余量和最终资源配额检查。
- 正常关闭、取消、错误和超时立即删除文件；进程被杀后的孤儿文件在下次进程初始化清理。
- 运行环境模型统一提供 provider/version、三个 WebView feature、可用/首选传输和
  fail-closed 状态。导入仍可完成，但缺少核心 feature 时授权页不得启用，runtime host
  仍执行最终拒绝。

## 5. Components to change

- Contract Registry、生成器产物、SDK/Mock/模板及 workspace 版本与 lockfile。
- Android WebView policy、bridge staging/transport、runtime handshake、应用初始化清理。
- 插件管理预检、独立诊断路由、授权门禁与相关 UI/runtime 测试。
- 权威开发文档、网站开发者页、Android/SDK README 与旧 ExecPlan supersession 说明。

## 6. Milestones and implementation steps

1. **契约与 SDK**：移除两个 capability 的 ArrayBuffer feature gate，生成新握手类型；
   SDK 0.2.0 在握手后选择模式、计算摘要并发送分片；Mock 覆盖现代、兼容和旧宿主。
2. **Android transport**：实现可测试的流式暂存状态机与严格校验，把两种帧接入 bridge，
   增加 ACK、timeout、并发和孤儿清理。
3. **诊断与门禁**：加入运行环境诊断页、预检摘要和不可运行提示，只对两个核心 feature
   做全局运行门禁。
4. **文档与验收**：同步活动规范，运行 tooling、Android、平台、lint、schema 和
   instrumentation 验证，记录本机不可执行项。

## 7. Validation plan

- `plugin-tooling`: `npm ci`, `npm run generate:check`, `npm run typecheck`,
  `npm test`, `npm run pack:check`。
- `android`: `gradlew test`, `:app:compileDebugAndroidTestKotlin`,
  `:app:compileReleaseKotlin`, `assembleDebug`；API 26/35 执行或交由阻塞 CI。
- `web/platform`: `npm ci`, `npm run typecheck`, `npm test`,
  `npm run test:integration`, `npm run test:e2e`。
- 对所有模板/示例运行 `tools/third-party-service-lint.cjs`；确认公共 schema 字节一致；
  最后运行 `git diff --check`、`git status --short` 和 secret/生成缓存审计。

## 8. Progress log

- 2026-08-05：创建计划；确认现有实现已把二进制写入暂存到文件，但只支持
  ArrayBuffer、声明没有 transport/SHA-256、没有孤儿启动清理，且尚无正式诊断页。
- 2026-08-05：记录用户决策：新二进制写入要求 SDK 0.2.0；新增独立环境诊断页；
  核心 WebView feature 缺失时允许安装但不得启用或运行。
- 2026-08-05：完成 Contract Registry 与八个生成物；SDK/CLI/create/template 协调到
  0.2.0。SDK 在握手前拒绝二进制写入，兼容旧布尔握手，并实现 ArrayBuffer SHA-256
  声明与 Base64URL 48 KiB 分片/逐片 ACK。Mock Host 和浏览器 smoke host 使用新 offer。
- 2026-08-05：完成统一 `PluginWebViewRuntimeEnvironment`、Android 双传输 bridge 与
  `PluginBinaryStagingManager`。状态机逐片严格校验、流式落盘并覆盖顺序、重复、大小、
  分片数、摘要、四并发、磁盘安全余量、deadline、取消/关闭和进程初始化孤儿清理。
  原生 resource store 继续执行 Blob/Cache item/plugin/global 配额。
- 2026-08-05：完成插件管理入口、独立诊断页、导入预检摘要和授权页门禁；当前 provider、
  版本、三项 feature 与协商模式使用同一实时模型。网络图片回归测试证明响应直接生成
  native resource handle 并可 `cache.promote`，未进入桥接 Base64。
- 2026-08-05：tooling `npm ci`、生成检查、typecheck、29 项测试和 `pack:check` 通过；
  Chrome 可执行探测在当前环境不可用后改用 Edge，真实浏览器 smoke 通过。Android 全部
  JVM 测试、debug instrumentation 编译、release Kotlin 编译及 debug APK 构建通过；
  仅报告既有 file-URL WebSettings 弃用警告。真实 JavaScript 双模式上传测试已加入
  instrumentation；本机短暂出现的 API 31 设备在执行前断开，API 26/35 运行交由阻塞 CI。
- 2026-08-05：平台 typecheck、17 项 unit 和 6 项 e2e 通过；未设置
  `TEST_DATABASE_URL`，2 项 PostgreSQL integration 按设计跳过。四个 Manifest
  模板/示例 lint、两组公共 schema 字节一致性和 `git diff --check` 通过；secret、
  构建产物和本机配置审计未发现本任务新增提交项。
- 2026-08-06：完成提交前代码评审并修复发现项：所有插件 envelope 字段改为严格 JSON
  类型读取，畸形二进制 chunk fail closed 并清理暂存；Android deadline 改用单调时钟；SDK
  在 transport 之外统一执行握手门禁，失败的二次握手会撤销旧协商，取消 hook 异常不再
  形成未捕获错误；取消发生在 SHA-256 计算期间时不会在摘要完成后继续上传。兼容模式
  instrumentation 改为收到逐片 ACK 后才发送下一片，并移除约 1 MiB 的内联字节脚本。
  新增握手门禁、重协商失败、取消异常、摘要准备期取消、非规范 Base64URL trailing bits
  与模式不匹配回归测试；最终 tooling suite 为 33/33 通过。

## 9. Decision log

- Decision：保持 protocol v2/runtime floor 2，握手做增量模式 offer。
  - Consequence：新 SDK 可识别旧宿主布尔握手；新宿主可明确拒绝旧二进制帧。
- Decision：Base64URL 每片 48 KiB并逐片 ACK。
  - Consequence：单个 String WebMessage 约 64 KiB，显著低于 512 KiB JSON 上限，且不会
    因同步循环把整份编码数据排入消息队列。
- Decision：进程异常终止后的清理发生在下次启动。
  - Consequence：不依赖 Android 不保证调用的 `onTerminate`/finally。

## 10. Risks and rollback

- 风险：厂商 WebView 的 String WebMessage 行为差异、Base64 解码异常、消息洪泛或暂存泄漏。
- 缓解：固定小分片、逐片 ACK、严格状态机、最大四并发、deadline、磁盘余量和启动清理；
  instrumentation 支持强制两种 offer，不依赖设备恰好缺少 ArrayBuffer。
- 回滚：把 Android bridge、SDK/tooling、Contract Registry、全部生成物与活动文档作为
  一个整体回滚。没有数据库迁移；已有 Blob、Cache、KV、插件包和 resource handle 不变。

## 11. Completion checklist

- [x] 目标、边界、设计、验证和回滚已记录。
- [x] Contract Registry 与所有生成物一致。
- [x] SDK/Mock/模板与 Android 两种传输完成。
- [x] 诊断、预检和启用门禁完成。
- [x] 分层验证完成；API 26/35 instrumentation 与 PostgreSQL integration 的环境项已明确移交 CI。
- [x] 提交前代码评审完成，全部发现项已修复并通过受影响回归。
- [x] 最终工作树、secret 和生成缓存审计完成；三处任务前用户改动保持不变。
