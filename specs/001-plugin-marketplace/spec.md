# Feature Specification: 插件大厅与投稿分发

**Feature Branch**: `dev`
**Created**: 2026-07-17
**Status**: Approved for implementation
**Input**: User description: "为现有第三方插件导入能力增加可投稿、自动校验、浏览、下载、更新和本机配置的平台。"

## User Value

- Target users: 浏览和安装插件的 BJTU MIS Android 用户、提交插件的 GitHub 开发者、处理异常插件的平台管理员。
- User problem: 当前用户只能手工输入 GitHub 链接，无法发现经过统一预检的插件；开发者没有公开投稿入口；插件也无法声明和读取用户在手机端配置的第三方参数。
- Desired outcome: 用户可从 Web 或 Android 大厅发现插件并安全安装已校验快照，开发者只需提交公开 GitHub 仓库链接，管理员可处理异常上架内容，插件配置仅保存在用户设备。
- Success criteria: 正常网络下，合规插件在提交后 3 分钟内完成校验并公开；任何包完整性不一致均阻止安装；平台不可用时不影响已安装插件运行。

## Scope

- In scope: Web 大厅与投稿、自动静态校验、版本快照分发、GitHub 身份与仓库权限验证、最小管理后台、Android 大厅与缓存、安装更新预检、声明式插件配置和安全存储。
- Out of scope: 私有仓库、插件在线构建、人工逐个审核、自动更新、评分评论收藏、下载榜单、动态恶意代码认证。
- Non-goals: 不替换现有受控插件权限和 WebView 沙箱；不允许平台或插件配置接口绕过邮件、作业等高风险操作的用户确认。
- Unknowns: 无。固定分类采用学业学习、校园生活、信息服务、效率工具、智能助手、其他。

## User Scenarios and Acceptance

### Primary Scenario: 浏览与安装

1. Given 用户打开 Android 插件大厅且平台存在已发布插件。
2. When 用户搜索或按分类筛选、查看详情并确认安装。
3. Then 应用下载精确的已校验版本，核对完整性并展示权限、远端来源和配置要求，确认后安装；必填配置和权限完成前插件保持不可用。

### Primary Scenario: 投稿与自动发布

1. Given 开发者已通过 GitHub 登录并对目标公开仓库具备管理或推送权限。
2. When 开发者提交仓库根链接。
3. Then 平台进入排队和校验状态；通过时自动发布并显示“未人工审核”，失败时给出可理解且不泄露敏感信息的原因。

### Primary Scenario: 配置插件

1. Given 已安装插件声明了配置项。
2. When 用户在手机端填写并保存配置。
3. Then 配置只保存在设备安全存储中，插件仅能从自己的本地页面按声明键读取；必填项缺失时插件不能启用。

### Edge Cases

- Empty state: 大厅无插件或筛选无结果时展示明确空状态和清除筛选入口。
- Loading or pending state: 投稿与校验显示可刷新状态；重复提交同一仓库不会创建并行校验。
- Network or upstream failure: 大厅回退到最近缓存；更新失败保留已安装版本；GitHub 暂时失败不会移除已发布版本。
- Offline behavior: 已安装插件继续运行，用户仍可查看和修改本机配置。
- Permission or authorization failure: 无仓库管理权限时拒绝投稿；配置读取权限或来源不符时返回结构化错误。
- Source removal: 仓库被持续确认删除、私有化或禁用后下架，已缓存快照停止新下载。
- Identity collision: 不同仓库不得占用同一插件 ID，外部插件不得覆盖内置插件。

## Requirements

### Functional Requirements

- FR-001: 系统必须提供可匿名浏览、搜索、分类筛选和查看详情的插件目录。
- FR-002: 系统必须只接受公开 GitHub 仓库根链接，并验证投稿者对仓库具备推送或管理权限。
- FR-003: 系统必须在发布前完成 manifest、版本、权限、来源、资源、大小、文件数、路径和完整性校验，且不得执行仓库代码。
- FR-004: 首次校验通过的插件必须自动发布并标记为“未人工审核”；失败更新不得替换现有公开版本。
- FR-005: 每个公开版本必须绑定不可变 commit 和两个完整性摘要，客户端必须在安装前复核。
- FR-006: 开发者必须能查看自己的投稿、主动重校验和下架；管理员必须能下架、恢复、查看记录和处理举报。
- FR-007: Android 必须默认展示大厅，并将现有直链导入保留为带有未校验警告的高级入口。
- FR-008: Android 必须缓存最近一次成功目录，并在目录服务不可用时保留已安装插件能力。
- FR-009: 插件必须能够声明类型化配置项，用户必须能够在安装后查看、填写、更新和清除配置。
- FR-010: 必填配置和权限未全部完成时插件不得启用；更新后仅保留定义兼容的配置值。
- FR-011: 插件只能从自身本地沙箱页面读取其声明过的配置键；远端允许来源不得读取配置。
- FR-012: 删除插件必须同时删除其本机配置；更新、安装或数据库操作失败时必须保留上一可用版本。

### Data and Interface Requirements

- Inputs: GitHub 仓库根链接、插件 manifest、目录搜索条件、用户填写的插件配置、举报原因。
- Outputs: 插件目录与详情、投稿和校验状态、不可变插件快照、安装和更新预检结果、结构化错误。
- Persistence: 平台保存用户、投稿、版本、校验、举报和审计数据；设备保存目录缓存、已安装插件和加密配置。
- External systems: GitHub 登录、公开仓库元数据和源代码快照。
- Public API, schema, route, or tool changes: 新增插件目录和投稿 API；manifest 支持 v2 市场信息与配置声明；新增按键读取配置的插件桥接方法。

### Security and Privacy Requirements

- Credentials, Cookie, token, or personal data handling: GitHub token 和设备插件配置必须加密保存；配置值不得进入平台、普通日志、崩溃信息或目录 API。
- User confirmation requirements: 安装、更新、权限变化和高风险插件能力继续要求明确确认。
- Workspace, file, archive, or generated output boundaries: 下载与解压必须限制大小、文件数、目标目录和条目类型，并阻止路径穿越和符号链接逃逸。
- Logging and fixture privacy rules: 测试不得包含真实账号、token、Cookie 或配置值；校验错误只记录仓库、版本和非敏感诊断。

## Constitution Alignment

- User safety and explicit authorization: 保留权限确认、高风险调用确认和安装更新确认；插件配置访问新增独立可见权限。
- Architecture boundaries: Android UI 经 repository 获取目录和安装状态；安全存储与桥接分别位于安全层和既有第三方服务边界。
- Local-first and offline recovery: 已安装插件及配置本地可用，目录失败回退缓存，失败更新不破坏旧版本。
- Testable parser/provider/repository/Agent tool changes: manifest、目录 provider、安装器、桥接和归档边界均使用 fixture、fake 或 MockWebServer 测试。
- Minimal dependencies and clean artifacts: 平台单独管理依赖；不提交数据库卷、插件快照、token、构建产物或本机配置。
- ExecPlan requirement for high-risk changes: 本功能涉及生产配置、OAuth token、安全存储、网络边界和 Android 桥接，必须维护同目录 ExecPlan。

## Acceptance Criteria

- AC-001: 25 MiB 以内的合规仓库在正常 GitHub 和平台网络下，95% 可在 3 分钟内从提交进入公开目录。
- AC-002: 被修改一个字节的归档或 dist 内容在所有测试中均被客户端拒绝安装。
- AC-003: 平台停机或 GitHub 不可用时，已安装插件仍可打开，用户仍可查看和编辑本机配置。
- AC-004: 必填配置缺失、配置权限未授予或调用页面不是本地沙箱时，配置读取成功率为 0%。
- AC-005: 平台 API、数据库、Web 页面和日志中均不存在设备插件配置值。
- AC-006: 更新校验、下载、解压、落盘或数据库保存任一步失败时，旧插件版本仍保持可打开。
