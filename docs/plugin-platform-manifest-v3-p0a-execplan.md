# ExecPlan: 插件平台 Manifest v3 / P0-A 安全与兼容基线

> **状态：已被取代，仅保留作历史与救援依据。** 当前有效计划为
> `docs/plugin-runtime-contract-p0-p1-execplan.md`。本计划曾替代
> `specs/001-plugin-marketplace/plan.md` 中的 Manifest v1/v2、聚合 origin、明文身份/
> 通用 HTTP 桥、
> commit 绑定 origin 和无 Room migration 等旧安全假设。冲突时以 constitution 原则 VII、
> `docs/third-party-services.md` 和 Manifest v3 schema 为准。

## 1. Purpose and user-visible outcome

- 用户场景：用户从插件大厅或高级 GitHub 入口安装静态 Web 插件，并在更新后继续使用稳定数据、受控校园能力和明确的权限边界。
- 当前问题：sandbox origin 绑定 commit；Manifest v2 将联网、资源、导航和桥接来源混为一体；桥可暴露明文凭据；更新重置授权并立即删除旧版本；HTTP 桥和 WebView 设置缺少正式 runtime 契约。
- 完成后的可观察行为：
  - 只运行 Manifest v3 插件，旧 v1/v2 插件显示兼容迁移与无桥救援入口，不自动删除数据。
  - 插件 origin 由 GitHub publisher subject 与插件 ID 决定，更新 commit 不改变 origin。
  - 远程 iframe、浏览器联网、外部导航和原生桥使用独立策略；桥只对本地主 frame 开放。
  - 插件使用加密、配额化、可快照回滚的 JSON KV 数据空间。
  - 校园请求只通过宿主登记的 MIS、AA、VE 只读策略，并复用既有会话。
  - 更新只确认新增权限和新增 origin，失败时旧包和旧数据仍可用。
- 成功标准：本计划的自动测试、构建、迁移与安全场景全部通过，或明确记录无法在本机执行的外部环境验证。

## 2. Repository context

- Android 工程：`android/`。
- Android 插件实现：`android/app/src/main/java/cn/edu/bjtu/mis/data/thirdparty/` 与 `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/ThirdPartyServiceScreens.kt`。
- Android 数据库：`android/app/src/main/java/cn/edu/bjtu/mis/data/db/AppDatabase.kt`，本计划从版本 10 升级到 11。
- 平台后端：`web/platform/`，当前是未跟踪目录；旧根目录 `platform/` 已处于用户删除状态，不恢复。
- Manifest、lint 与文档：`docs/third-party-service-manifest.schema.json`、`web/assets/schemas/`、`tools/third-party-service-lint.cjs`、`web/developers.html`、`third-party-plugins/`。
- 当前工作树包含大量用户未提交改动。实施只在上述插件平台范围叠加，不回退、清理或格式化无关文件。

## 3. Constraints and non-goals

- 遵守 `.specify/memory/constitution.md`，尤其是用户授权、架构边界、可测试性和高风险 ExecPlan 要求。
- 不恢复旧 `platform/`；不直接维护 `android/app/src/main/assets/public/`。
- 不提交 secret、本机配置、APK/AAB、数据库卷、构建缓存、`node_modules` 或 Open WebUI 生成资产。
- v1/v2 为破坏性停用，不保留运行兼容；只保留显式救援和迁移说明。
- 本轮不实现 npm SDK/CLI、Blob/大文件、WorkManager 插件任务、OAuth、通用 SAF、SSE/WebSocket、AI Broker、签名 `.bjtup` 或 P2 生态能力。

## 4. Proposed design

### Manifest/runtime

- Manifest v3 增加 runtime version、required/optional capabilities、data schema、migration entrypoint、connect/media/frame/navigation/bridge origins。
- `allowed_origins` 和 v1/v2 被 validator、平台 worker、Android 安装器和共享 lint 拒绝。
- `bridge_origins` 必须精确为 `["self"]`。
- runtime bridge 使用 `protocol_version`、`request_id`、统一错误对象，并提供 runtime info、capability、ready、storage 与 lifecycle 契约。

### Publisher identity and updates

- `publisher_subject_id` 首次发布时固定为 `github-owner:<numeric-id>`；sandbox host 使用 publisher subject + plugin ID 的 SHA-256。
- 平台 owner 转移必须进入待审核状态；管理员批准后保留 subject，只更新 GitHub owner 绑定。
- Android Room 10→11 保存 publisher subject、data schema、兼容状态、验证级别和上一版本元数据。
- 安装器保留一个上一版本目录；repository 通过预检、数据快照、数据库切换与失败恢复完成原子更新。

### WebView trust boundary

- 仅在 `DOCUMENT_START_SCRIPT` 与 `WEB_MESSAGE_LISTENER` 同时可用时运行 v3。
- 使用 `MIXED_CONTENT_NEVER_ALLOW`，关闭第三方 Cookie、文件访问、多窗口和下载。
- 本地响应生成 CSP、Permissions-Policy、nosniff 与 Referrer-Policy。
- bridge 只注入稳定本地 origin，并拒绝非 main frame。
- `navigation_origins` 只通过用户手势打开系统浏览器；远程 iframe 无桥、无顶层导航、无下载与弹窗。

### Storage and migration

- 每个 publisher+plugin 使用 AES-GCM JSON KV，10 MiB 总配额、256 KiB 单项、1024 keys。
- 数据写入采用 Mutex、临时文件、同步与原子替换。
- data schema 提升必须在独立迁移 origin 中对影子 KV 执行本地 migration entrypoint；30 秒内提交，否则回滚。
- 删除时清理 KV、配置、快照、包和 WebStorage；失败写 tombstone，在后续启动重试。

### Campus proxy

- `campus.request` 只允许 GET/HEAD、相对路径、注册 query 与 Accept，15 秒超时、5 MiB 流式上限。
- MIS、AA、VE 路径由宿主 registry 固定，并逐路径映射到既有细粒度权限。
- 代理复用 SessionManager/provider，剥离 Cookie、Set-Cookie 与认证头，阻止越域重定向。
- Coremail、知行、就业与所有写请求不进入代理。

## 5. Files and components to change

- `web/platform/`：SQL migration、publisher 身份、API v2、worker、transfer 管理和测试。
- Android third-party/data/db/UI 组件：Manifest v3、Room 11、稳定 sandbox、storage、runtime、校园代理、更新/删除恢复和测试。
- Manifest schema、lint、插件模板、开发者文档、Web 大厅和 CI workflow。
- 生成文件处理：不得提交 Android/Open WebUI build 输出；Web 静态源码可提交，生成的 Android assets 不提交。

## 6. Milestones

### Milestone 1: 契约、平台和 publisher identity

- 预期行为：平台只发布 v3，API v2 返回 publisher/runtime/origin 元数据，owner 转移被冻结并可审核。
- 验证：平台 typecheck、unit、integration、e2e、共享 schema/lint。
- 状态：completed

### Milestone 2: Android 持久化与原子更新

- 预期行为：Room 11 迁移保留旧记录并禁用；stable origin、KV、快照、迁移和回滚可测试。
- 验证：Android JVM tests、Room instrumentation。
- 状态：completed

### Milestone 3: WebView runtime 与校园代理

- 预期行为：document-start bridge、main-frame 限制、安全头、origin 策略、lifecycle 和 MIS/AA/VE 只读代理生效。
- 验证：Android JVM 与 instrumentation 安全场景。
- 状态：completed

### Milestone 4: UI、文档、CI 与发布验证

- 预期行为：兼容迁移、差异授权、publisher/runtime 信息可见；CI 覆盖平台、Android 与 API 26/35。
- 验证：全量构建、测试、diff/secret/产物检查。
- 状态：completed

## 7. Validation plan

- 平台：`Set-Location web/platform; npm run typecheck; npm test; npm run test:integration; npm run test:e2e`。
- Android：`Set-Location android; .\gradlew.bat test; .\gradlew.bat assembleDebug`。
- Instrumentation：范围内 connected/device tests；CI 使用 API 26 与 35。
- Open WebUI：本轮不修改 local-first；若 Android 打包触发资产构建，记录结果，不直接提交生成目录。
- Schema/lint：运行模板与示例 lint，并验证三份 schema 同步。
- 完成前：`git diff --check`、`git status --short`、secret/构建产物检查。

## 8. Risks and rollback

- Room 升级风险：必须提供 10→11 migration test；应用级回滚使用保留 schema 11 的前向 hotfix，不降级安装 v1.4.0。
- WebView 能力缺失：fail closed，显示兼容提示，不使用不安全 fallback。
- 更新或迁移失败：恢复上一包、Manifest、授权和 KV；保留 staged 诊断，不破坏 active 版本。
- publisher 变化：未审核转移不切换 subject；高级直链 owner 变化拒绝原位更新。
- 删除清理失败：记录 tombstone 并重试，不静默声称数据已清理。
- 平台发布回滚：保留旧 API 只读路径，新增 SQL migration 采用可前向兼容字段/表，不删除旧数据。

## 9. Progress log

- 2026-07-28 19:30 +08:00：创建计划；记录当前工作树含大量用户改动、旧 `platform/` 删除和新 `web/platform/` 未跟踪状态。
- 2026-07-28：完成 Manifest v3、平台 `/api/v2`、不可变 GitHub owner/repository 身份、owner 转移冻结与管理员审批；旧 `/api/v1` 写入口返回 `410 Gone`。
- 2026-07-28：完成 Room 10→11、稳定 sandbox origin、加密事务型 KV、影子迁移、原子回滚、增量授权、删除 tombstone 与 legacy rescue。
- 2026-07-28：完成 document-start/main-frame bridge、WebView 安全策略、生命周期环境事件和 MIS/AA/VE 只读校园代理。
- 2026-07-28：完成大厅/详情/投稿展示、开发者文档、模板、静态 lint、部署配置与阻塞 CI。
- 2026-07-28：本机平台 typecheck、16 个 unit、3 个 e2e、Android 全量 JVM、AndroidTest 编译、debug APK、三组 Manifest lint、schema 镜像与 JS 语法检查通过。数据库集成用例因未设置 `TEST_DATABASE_URL` 跳过；本机无可用 adb，API 26/35 instrumentation 已编译并交由 CI 执行。
- 2026-07-28：constitution 升级到 1.1.0 并新增原则 VII；README、AGENTS、PLANS、Spec Kit 模板、旧 marketplace spec/plan 和公开开发者文档均明确由 Manifest v3 / P0-A 覆盖旧安全策略。

## 10. Decision log

- Manifest v1/v2 在新 runtime 中立即禁用，不长期双栈。
- 明文凭据接口删除，以宿主 MIS/AA/VE 只读代理替代。
- publisher subject 使用 GitHub owner 数值 ID 初始化；管理员批准转移时保持 subject 连续。
- 普通第三方联网只使用浏览器 fetch；不保留原生 `app.http_request`。
- 远程 iframe 允许 same-origin DOM/存储，但禁用第三方 Cookie。
- 高级 GitHub 直链导入继续面向所有用户，并显示未平台验证风险。
- 存储只提供应用内快照，不提供外部导出。
- WebView Beta 自动脚本不阻塞托管 CI。
- Manifest v3 / P0-A 通过 constitution 原则 VII 成为唯一有效插件安全基线；旧 marketplace plan 只保留为带 superseded 标记的历史记录。

## 11. Completion checklist

- [x] Manifest v3、平台 API v2 与 publisher identity 完成。
- [x] Room 10→11、stable origin、KV、迁移与回滚完成。
- [x] WebView runtime、安全策略、生命周期和校园代理完成。
- [x] UI、文档、模板、lint 与 CI 完成。
- [x] 范围内测试和构建通过或记录外部阻塞。
- [x] 已检查工作树、diff、secret 与生成产物，没有回退用户改动。
