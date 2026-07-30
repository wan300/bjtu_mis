# Archived ExecPlan: 插件大厅、投稿平台与 Android 配置能力

**Status**: Superseded on 2026-07-29

> 本文件保留 2026-07-17 至 2026-07-22 的实现历史，不再定义当前安全策略。其 Manifest
> v1/v2 兼容、`allowed_origins`、commit 绑定 sandbox、明文身份/通用 HTTP 桥、
> 立即清理旧版本和“无 Room migration”等假设，均由 constitution 原则 VII、
> `docs/third-party-services.md`、contract_v1 schema 以及
> `docs/plugin-runtime-contract-p0-p1-execplan.md` 覆盖。后续实现、评审、发布和回滚
> MUST 只使用上述当前基线；本文件中的冲突内容不得作为兼容承诺或安全例外。

## 当前规范替代项

- 新客户端只安装、更新和运行 Manifest v3 / contract_v1；v1/v2 与 P0-A v3
  仅保留无桥、无网络的救援入口。
- `/api/v3`、不可变 GitHub owner/repository 数值身份和稳定 publisher+plugin origin
  替代旧目录与 commit origin 假设；`/api/v2` 冻结为旧客户端只读目录。
- connect/media/frame/navigation origin 分离；`bridge_origins` 不再由作者声明，
  self bridge 是宿主固定不变量且只服务稳定本地 main frame。
- `storage.kv@2`、Blob/Cache、影子 migration、上一版本包与数据快照、
  Capability 增量授权和 tombstone 清理替代旧的非事务更新策略。
- 校园访问只能使用 MIS/AA/VE 只读 `campus.request` registry；明文凭据读取和通用原生
  HTTP bridge 被永久移除。

<details>
<summary>展开原始历史 ExecPlan（不可作为当前规范）</summary>

## 1. Title

ExecPlan: 为现有第三方服务增加可投稿、可校验、可分发、可配置的插件大厅。

## 2. Purpose and user-visible outcome

- 用户场景：用户从 Web 或 Android 浏览插件，在手机端安装平台已校验快照，并配置第三方参数。
- 当前问题：仅支持手工 GitHub URL 导入，没有公开目录、统一校验记录或声明式配置。
- 完成后的可观察行为：开发者提交公开仓库后自动校验发布；Android 展示大厅、已安装和高级导入；配置安全保存在设备并由插件受控读取。
- 成功标准：以 `spec.md` 的 AC-001 至 AC-006 为准。

## 3. Repository context

- Android 工程：`android/`，现有实现集中在 `data/thirdparty` 和 `ui/screens/ThirdPartyServiceScreens.kt`。
- Web：`web/` 为 Nginx 直接提供的静态站点。
- 平台：`web/platform/` 独立管理 Node 依赖、API、worker、SQL migration 和测试，与静态前端统一位于 `web/`。
- 部署：`deploy/nginx/bjtu.cc.conf` 现为纯静态配置；服务器具备 Docker、Node、1.6 GiB 内存和约 11 GiB 可用磁盘。
- 禁止提交：环境文件、OAuth 密钥、数据库卷、插件快照、APK/AAB、Android 本机配置和生成的 WebView 资产。

## 4. Constraints and non-goals

- 不执行插件仓库脚本，不构建插件，不支持私有仓库或自动更新。
- 不修改邮件、作业等高风险能力的确认语义。
- 不新增 Room 表；目录缓存使用原子 JSON 文件，插件配置使用独立加密文件。
- 平台自动发布但必须显示未人工审核；管理员下架优先于任何后台任务。
- 许可证仅展示不拦截，这是用户明确接受的再分发风险。

## 5. Research notes

- `git status --short` 在开始时为空，当前分支为 `dev`。
- 数据库版本为 10，第三方服务表由迁移 7→8 创建、8→9 增加 digest。
- 现有安装器已具备 GitHub URL、25 MiB 下载、50 MiB 解压、1000 条目、路径边界和 dist digest 校验。
- 现有 manifest 仅支持 schema v1；Web 与 docs schema 有同步测试。
- 现有站点为静态 Nginx；API 将通过同域 `/api/v1/` 反代到 `127.0.0.1:15020`。

## 6. Proposed design

- Manifest：新客户端同时接受 v1/v2；v2 增加 marketplace 与 configuration，并要求 SemVer 和 `app.configuration.read`。
- 平台：Node 22/TypeScript/Fastify、PostgreSQL 16、单 worker、数据库任务队列和本机不可变 artifact volume；无 Redis。
- 发布状态：queued → validating → published/rejected；更新失败保持旧版；连续三次确认源不可用后自动下架。
- 快照：worker 静态解析 GitHub zip，生成只含 manifest 与 dist 的规范化 zip，记录 archive SHA-256 和 dist digest，只保留当前与上一发布版。
- Android：目录 repository 负责 API 与本地缓存；安装器支持带预期 digest 的平台包；写入新 commit 目录并在数据库成功后清理旧目录。
- 配置：所有值统一 AES-GCM 加密；配置定义不含值；更新仅保留 key/type/secret 语义一致的值。
- 桥接：`app.get_configuration` 需要声明权限并限制在本地 sandbox origin。

## 7. Files and components to change

- `docs/`、`web/assets/schemas/`、`tools/`：schema v2、开发者文档和共享 lint。
- `web/platform/`、`deploy/`、`web/plugins/`：API、worker、数据库、容器、Nginx 和 Web 大厅。
- `android/app/src/main/java/.../thirdparty` 与相关 UI/测试：目录、安装、配置、安全存储和桥接。

## 8. Milestones

### Milestone 1: 规格与 Manifest v3

- 目标：v1 兼容、v2 校验、配置定义和同步 schema 测试。
- 验证：Node lint fixtures 与 Android validator tests。
- 完成状态：completed

### Milestone 2: 平台与 Web

- 目标：可运行 API/worker/PostgreSQL、投稿状态机、artifact 分发和 Web 页面。
- 验证：typecheck、unit、integration、e2e、Compose config 和 health check。
- 完成状态：completed（数据库集成测试需提供 TEST_DATABASE_URL）

### Milestone 3: Android 大厅与配置

- 目标：大厅、缓存、完整性安装、配置表单/加密存储/桥接和更新回滚。
- 验证：Gradle unit tests、assembleDebug 和模拟器主流程。
- 完成状态：completed（模拟器人工流程留待有设备环境执行）

### Milestone 4: 部署与回滚演练

- 目标：Nginx 同域 API、容器健康、备份和回滚文档。
- 验证：Compose config、数据库 migration、`nginx -t` 和只读 smoke test。
- 完成状态：in progress

## 9. Step-by-step implementation plan

1. 完成 v2 models/schema/validator/lint/fixtures，保持 v1 直接导入兼容。
2. 创建平台数据库 migration、配置、领域模型和共享静态校验器。
3. 实现公开目录、OAuth 会话、投稿、worker、artifact、开发者和管理员 API。
4. 增加静态 Web 大厅、详情、投稿、我的插件和管理页面。
5. 实现 Android catalog client/repository/cache 和平台 artifact 预检安装。
6. 实现配置定义、加密 store、启用状态协调、配置 UI 和本地 origin 桥接。
7. 补齐自动测试，运行平台、Android、Web 和部署验证。
8. 更新开发者文档、部署文档、ExecPlan 进度/决策/风险并检查最终 diff。

## 10. Validation plan

- 平台：`Set-Location web/platform; npm ci; npm run typecheck; npm test; npm run test:integration; npm run test:e2e`。
- Android：`Set-Location android; .\gradlew.bat test; .\gradlew.bat assembleDebug`。
- lint：`node tools/third-party-service-lint.cjs third-party-plugins/examples/profile-timetable`。
- 部署：Compose config、PostgreSQL migration、API health、`nginx -t`。
- 手工：Web 投稿/管理流程与 Android 大厅/安装/配置/更新/高级导入。

## 11. Progress log

- 2026-07-17：创建 feature spec、质量检查表和 ExecPlan；确认工作树干净且无 Spec Kit hooks。
- 2026-07-17：完成 schema v2、共享 lint、平台 API/worker/PostgreSQL migration、Web 五个页面、Docker Compose 与 Nginx 反代；平台 typecheck、unit、health e2e 和 Compose config 通过。
- 2026-07-17：完成 Android 目录缓存、平台双 digest 安装、旧版本延迟清理、加密配置、配置桥接和大厅/已安装 UI；Kotlin 编译通过，首次 310 项测试发现并修正一个新增 validator 测试用例。
- 2026-07-17：复跑 Android 310 项 JVM 测试与 `assembleDebug` 均通过；平台 typecheck、6 项 unit、health e2e、Compose config、共享 lint/schema 校验通过。数据库 integration 因未设置 `TEST_DATABASE_URL` 跳过；Docker daemon 未运行，镜像构建与 `nginx -t` 留待部署机执行。
- 2026-07-22：合并前评审发现 artifact 从 `/tmp` 跨卷 `rename`、平台测试被通用 ignore 规则排除、投稿并发去重竞态，以及损坏配置无法恢复删除。修正为 artifact 卷内临时文件原子替换、数据库部分唯一索引、跟踪平台测试，并为 Android 删除流程增加目录暂存与失败恢复。平台后续整理到 `web/platform/`。
- 2026-07-22：复核后进一步修正 WebView 配置桥接来源验证、manifest 数组类型校验、平台与 Android ZIP 目录条目计数和目录缓存筛选回退；Android `test assembleDebug`、Open WebUI 89 项 Vitest、平台 typecheck/unit/e2e、共享 lint 与生产 Compose config 均通过，secret/产物检查无异常。数据库 integration 仍因未设置 `TEST_DATABASE_URL` 跳过。

## 12. Decision log

- 使用传统 GitHub OAuth 的公开只读权限，不申请 repo/public_repo；无法证明写权限时拒绝投稿。
- 自动发布并显示未人工审核，管理员下架具有最高优先级。
- 平台缓存规范化快照；Android 不从默认分支实时安装大厅插件。
- 配置全部加密，不仅加密标记为 secret 的值，以简化泄露边界。
- 不新增 Room migration，目录缓存和配置分别使用普通原子文件与加密文件。
- 按本轮合并要求，GitHub repository API 返回 304 时不重新验证投稿者权限的问题暂缓处理，不计入当前合并阻塞项。

## 13. Risks and rollback plan

- 风险：公开仓库无许可证仍被缓存。缓解：页面展示许可证状态和未人工审核；保留管理员紧急下架。
- 风险：OAuth token 泄露。缓解：应用层 AES-GCM、环境密钥、HttpOnly/SameSite cookie、日志脱敏和轮换说明。
- 风险：恶意压缩包。缓解：双端大小、条目、类型、路径和 digest 校验，worker 非 root、只读根文件系统和资源限制。
- 风险：更新破坏已安装插件。缓解：commit 目录先落盘、数据库成功后切换、失败保留旧目录和授权状态。
- 平台回滚：恢复旧 Nginx 配置并停止平台 Compose；静态站点继续服务，Android 使用缓存和已安装插件。
- Android 回滚：回退目录/配置相关代码；无 Room migration，已安装目录和原表仍可读取；新增加密配置文件可安全忽略。

## 14. Completion checklist

- [x] 规格、质量清单和 ExecPlan 已创建。
- [x] v2 schema/validator/lint 与 fixture 完成。
- [x] 平台、Web、Android 功能完成。
- [x] 范围内测试与构建通过或记录外部环境限制。
- [x] 部署、备份和回滚文档更新。
- [x] 最终 `git status --short`、`git diff` 和 secret/产物检查完成。

</details>
