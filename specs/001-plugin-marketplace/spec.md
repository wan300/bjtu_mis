# Feature Specification: 插件大厅与投稿分发

**Feature Branch**: `dev`
**Created**: 2026-07-17
**Status**: Amended — Manifest v3 / P0-A authoritative
**Input**: User description: "为现有第三方插件导入能力增加可投稿、自动校验、浏览、下载、更新和本机配置的平台。"

> **Security amendment (2026-07-28):** 本规格最初形成时的 Manifest v2、聚合 origin、
> commit 绑定 sandbox、明文身份/通用 HTTP bridge 和无事务数据空间假设已全部失效。
> 当前需求必须服从
> constitution 原则 VII、`docs/third-party-services.md`、Manifest v3 schema 与
> `docs/plugin-platform-manifest-v3-p0a-execplan.md`；任何冲突条款均由该基线覆盖。

## User Value

- Target users: 浏览和安装插件的 BJTU MIS Android 用户、提交插件的 GitHub 开发者、处理异常插件的平台管理员。
- User problem: 除插件发现与投稿外，旧策略还会让插件身份随 commit 漂移、远程来源共享桥接边界、更新缺少数据迁移与原子回滚，并可能把校园凭据暴露给插件。
- Desired outcome: 用户只运行具备稳定发布者身份、最小权限、分离 origin、事务型本地数据与只读校园代理的 Manifest v3 插件；旧插件仅能安全救援数据。
- Success criteria: 正常网络下合规 v3 插件可发布和安装；完整性、身份、兼容性、权限、迁移或安全检查失败时均不切换当前版本，平台不可用时不影响已安装 v3 插件运行。

## Scope

- In scope: Web 大厅与投稿、Manifest v3 静态校验、不可变版本分发、GitHub 数值身份、owner 转移管理、Android 安装更新预检、稳定 WebView origin、runtime/capability、事务型 KV、增量授权、迁移回滚、声明式配置和只读校园代理。
- Out of scope: 私有仓库、插件在线构建、人工逐个审核、自动后台更新、评分评论收藏、动态恶意代码认证，以及 P1/P2 的 SAF、OAuth、后台任务、AI Broker 和签名包治理。
- Non-goals: 不为 v1/v2 提供运行兼容，不向远程 frame 提供桥，不提供明文凭据读取或通用原生 HTTP，不允许任何插件绕过邮件、作业、选课等高风险操作的逐次确认。
- Unknowns: 无。固定分类采用学业学习、校园生活、信息服务、效率工具、智能助手、其他。

## User Scenarios and Acceptance

### Primary Scenario: 浏览与安装

1. Given 用户打开 Android 插件大厅且平台存在已发布插件。
2. When 用户搜索或按分类筛选、查看详情并确认安装。
3. Then 应用下载精确的已校验 v3 版本，核对 artifact、publisher subject、runtime、capability、权限与四类 origin，确认后安装；必填配置和 required 权限完成前插件保持不可用。

### Primary Scenario: 投稿与自动发布

1. Given 开发者已通过 GitHub 登录并对目标公开仓库具备管理或推送权限。
2. When 开发者提交仓库根链接。
3. Then 平台进入排队和校验状态；通过时自动发布并显示“未人工审核”，失败时给出可理解且不泄露敏感信息的原因。

### Primary Scenario: 更新与迁移插件

1. Given 已安装 v3 插件发布新版本并提高 `data_schema_version`。
2. When 用户确认新增 required 权限与新增 origin。
3. Then 宿主在无网络、无校园权限的临时 origin 中迁移影子 KV，只有显式提交成功才切换包、Manifest、权限和数据；失败时原子恢复上一版本。

### Edge Cases

- Empty state: 大厅无插件或筛选无结果时展示明确空状态和清除筛选入口。
- Loading or pending state: 投稿与校验显示可刷新状态；重复提交同一仓库不会创建并行校验。
- Network or upstream failure: 大厅回退到最近缓存；更新失败保留已安装版本；GitHub 暂时失败不会移除已发布版本。
- Offline behavior: 已安装的兼容 v3 插件继续运行；legacy 插件只能进入无桥、无网络的救援入口。
- Permission or authorization failure: 无仓库管理权限时拒绝投稿；publisher 转移冻结更新；required 权限被拒绝时不切换版本；桥接来源不符时返回结构化错误。
- Source removal: 仓库被持续确认删除、私有化或禁用后下架，已缓存快照停止新下载。
- Identity collision: 不同 GitHub repository 数值 ID 不得占用同一插件 ID，外部插件不得覆盖内置插件；仓库改名保持身份，owner 转移必须管理员批准。

## Requirements

### Functional Requirements

- FR-001: 系统必须提供可匿名浏览、搜索、分类筛选和查看详情的插件目录。
- FR-002: 系统必须只接受公开 GitHub 仓库根链接，验证投稿者权限，并保存不可变 repository 数值 ID 与首次发布时固定的 `github-owner:<id>` publisher subject。
- FR-003: 系统必须只发布 Manifest v3，并在发布前完成 runtime、capability、权限、四类 origin、资源、大小、文件数、路径和完整性校验；不得执行仓库代码。
- FR-004: 首次校验通过的插件必须自动发布并标记为“未人工审核”；失败更新不得替换现有公开版本。
- FR-005: 每个公开版本必须绑定不可变 commit 和两个完整性摘要，客户端必须在安装前复核。
- FR-006: 开发者必须能查看自己的投稿、主动重校验和下架；管理员必须能下架、恢复、查看记录和处理举报。
- FR-007: Android 必须默认展示 `/api/v2` 大厅，并将直链导入保留为带未验证警告的高级入口；直链更新检测到 owner 转移时必须拒绝原位更新。
- FR-008: Android 必须缓存最近一次成功目录，并在目录服务不可用时保留已安装插件能力。
- FR-009: 插件必须声明 runtime/capability、required/optional 权限、配置、data schema，以及彼此独立的 connect/media/frame/navigation origin；`bridge_origins` 必须严格为 `["self"]`。
- FR-010: 必填配置和 required 权限未完成时插件不得启用；更新必须保留仍声明且已授予的权限、撤销删除项，并只向用户展示新增权限与 origin。
- FR-011: 原生桥只能存在于稳定本地 origin 的 main frame，消息 source origin 必须精确匹配；远程 frame、v1/v2 与 legacy rescue 不得获得桥。
- FR-012: 删除插件必须清理 KV、配置、快照、包和稳定 origin WebStorage；失败必须写入 tombstone 并在后续启动重试，legacy 数据只能由用户明确删除。
- FR-013: `app.storage` 必须按 publisher+plugin 隔离，使用 AES-GCM、10 MiB/256 KiB/1024 keys 配额和原子写入；data schema 不得降低，提升必须通过影子 KV migration，更新失败必须恢复上一包、Manifest、权限和 KV 快照。
- FR-014: `campus.request` 只允许 registry 中 MIS/AA/VE 的 `GET`/`HEAD` 只读 path+query，必须限制 15 秒与 5 MiB，并剥离 Cookie、认证头与不安全响应头；越界重定向必须失败。

### Data and Interface Requirements

- Inputs: GitHub 仓库根链接、插件 manifest、目录搜索条件、用户填写的插件配置、举报原因。
- Outputs: 插件目录与详情、投稿和校验状态、不可变插件快照、安装和更新预检结果、结构化错误。
- Persistence: 平台保存用户、投稿、版本、publisher/repository 身份、校验、举报和审计数据；设备通过 Room 11 保存兼容与上一版本元数据，并保存加密配置、事务型 KV 和回滚快照。
- External systems: GitHub 登录、公开仓库元数据和源代码快照。
- Public API, schema, route, or tool changes: `/api/v2` 与 Manifest v3 是公开契约；bridge 使用 protocol v1、统一错误、runtime/capability/lifecycle、`app.storage`、声明式配置读取和 `campus.request`。旧 `/api/v1` 只保留迁移期只读能力。

### Security and Privacy Requirements

- Credentials, Cookie, token, or personal data handling: GitHub token、设备插件配置和 KV 必须加密保存；只有当前 Manifest 已声明的配置 key 可经稳定本地 main-frame bridge 返回。校园凭据、Cookie 与认证头不得进入插件，任何配置值不得进入平台、普通日志、崩溃信息或目录 API。
- User confirmation requirements: 安装、更新、新增 required 权限、新增 origin、删除和领域写操作必须保持明确确认；拒绝新增 required 权限时不得切换版本。
- Workspace, file, archive, or generated output boundaries: 下载与解压必须限制大小、文件数、目标目录和条目类型，并阻止路径穿越和符号链接逃逸。
- Logging and fixture privacy rules: 测试不得包含真实账号、token、Cookie、配置值或校园响应；结构化错误只包含安全诊断，不回显请求凭据或敏感响应头。

## Constitution Alignment

- User safety and explicit authorization: 保留权限确认、高风险调用确认和安装更新确认；插件配置访问新增独立可见权限。
- Architecture boundaries: Android UI 经 repository 获取目录和安装状态；安全存储与桥接分别位于安全层和既有第三方服务边界。
- Local-first and offline recovery: 已安装插件及配置本地可用，目录失败回退缓存，失败更新不破坏旧版本。
- Testable parser/provider/repository/Agent tool changes: manifest、目录 provider、安装器、桥接和归档边界均使用 fixture、fake 或 MockWebServer 测试。
- Minimal dependencies and clean artifacts: 平台单独管理依赖；不提交数据库卷、插件快照、token、构建产物或本机配置。
- ExecPlan requirement for high-risk changes: 本功能涉及生产配置、OAuth token、安全存储、网络边界和 Android 桥接，必须维护当前有效的 `docs/plugin-platform-manifest-v3-p0a-execplan.md`。
- Manifest v3 / P0-A zero-trust baseline: v1/v2、`allowed_origins`、远程桥、明文凭据 API 和通用原生 HTTP 均不可恢复；安全策略以 constitution 原则 VII 和 v3 文档为准。

## Acceptance Criteria

- AC-001: 25 MiB 以内的合规仓库在正常 GitHub 和平台网络下，95% 可在 3 分钟内从提交进入公开目录。
- AC-002: 被修改一个字节的归档或 dist 内容在所有测试中均被客户端拒绝安装。
- AC-003: 平台停机或 GitHub 不可用时，已安装兼容 v3 插件仍可打开；v1/v2 只能进入无桥、无网络救援入口。
- AC-004: 调用页面不是稳定本地 main frame、source origin 不匹配或 capability/权限未授予时，原生桥能力成功率为 0%。
- AC-005: 平台 API、数据库、Web 页面和日志中均不存在设备插件配置值。
- AC-006: 更新校验、下载、解压、迁移、落盘或数据库保存任一步失败时，上一包、Manifest、权限和 KV 均保持可恢复。
- AC-007: `identity.get_credentials`、`identity.credentials.read`、`app.http_request`、远程 frame bridge 和未登记校园 path 的成功调用数为 0。
