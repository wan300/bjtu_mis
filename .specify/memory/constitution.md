<!--
Sync Impact Report
Version change: 1.1.0 -> 1.2.0
Modified principles: IV. 外部系统与 Agent 工具变更必须可测试（增加契约生成、Capability、命令幂等、网络和资源测试）; VII. 插件平台安全基线不得降级（以 contract_v1 取代 P0-A，并把 bridge origin 固定为宿主不变量）
Added principles: none
Added sections: none
Removed sections: none
Templates requiring updates: .specify/templates/spec-template.md (updated); .specify/templates/plan-template.md (updated); .specify/templates/tasks-template.md (updated)
Runtime guidance requiring updates: AGENTS.md (updated); PLANS.md (updated); README.md (updated); android/README.md (updated); docs/third-party-services.md (updated); web/developers.html (updated); web/platform/README.md (updated)
Superseded feature artifacts: docs/plugin-platform-manifest-v3-p0a-execplan.md (superseded by docs/plugin-runtime-contract-p0-p1-execplan.md); specs/001-plugin-marketplace/* remain historical
Deferred follow-ups: P2 下载队列、设备能力、声明式扩展点和正式诊断面板
-->

# BJTU MIS Android Constitution

## Core Principles

### I. 用户安全与明确授权优先

BJTU MIS Android MUST 保护校园账号、密码、Cookie、token、邮件内容、验证码、作业附件和个人学习数据。任何会发送邮件、提交作业、选课、删除数据、写入本地工作区、导出文件或改变上游校园系统状态的能力，MUST 保留明确的用户确认、可理解的错误反馈和可追踪的调用边界。Agent MUST NOT 静默发送邮件或自动提交课程平台作业。

Rationale: 本应用直接处理校园身份和学习事务，错误自动化会造成账号、隐私、学业和信任风险。

### II. 架构边界不可绕过

Android UI MUST 通过 repository、container 或既有状态模型获取数据，不得直接访问校园服务。provider 负责上游系统访问和会话协议，parser 负责把 HTML/JSON 转换为 model，repository 负责缓存和业务编排，db 负责 Room entity/DAO/迁移，security 负责凭据和 Cookie 安全存储，sync 负责后台同步和会话保活。Open WebUI 原生能力 MUST 通过既有 Capacitor 插件、handoff 和 `src/lib/local-first/` 工具注册路径接入。

Rationale: 清晰边界让校园服务适配、缓存、离线能力、安全存储和 UI 状态可以分别测试和维护。

### III. 本地优先与离线可恢复

用户已经同步的数据 SHOULD 在校园服务不可用、网络失败或离线时继续可读。新增持久化能力 MUST 使用项目既有 Room、DataStore、加密凭据或安全文件路径，并定义刷新、过期、失败和空状态反馈。同步和会话保活逻辑 MUST 避免破坏已有本地快照，除非用户明确触发删除或重置。

Rationale: 校园服务和网络并不总是稳定，本应用的核心价值包括本地快照和离线访问。

### IV. 外部系统、Agent 工具与插件契约变更必须可测试

parser 变更 MUST 使用 fixture 覆盖成功、缺字段和上游结构变化。provider/repository 变更 SHOULD 使用 MockWebServer、fixture 或 fake/store 模式，不依赖真实校园账号或线上服务。Agent 文件、归档、文档、代码、邮件和结果打包工具变更 MUST 覆盖 workspace 边界、路径穿越、大小限制、条目数量、确认流程和错误反馈。Open WebUI local-first、handoff、工具参数或流式输出变更 MUST 运行对应 Vitest。

第三方插件平台、Manifest、WebView runtime、Capability、存储、更新、网络或校园代理变更 MUST 覆盖契约生成确定性、生成物漂移、v3 schema、v1/v2 与 P0-A 拒绝、稳定发布者主体、main-frame/source-origin 桥接约束、授权与逐次确认、命令幂等、SSRF/重定向/Header/Cookie 边界、配额、迁移与回滚以及校园只读 registry。生成的 TypeScript、Kotlin、Schema、lint 和开发者文档 MUST 由同一 Capability Contract Registry 派生，并由阻塞测试证明一致。

Rationale: 上游页面和工具输入不受本项目控制，测试必须固定风险边界并防止回归。

### V. 最小依赖与干净提交

项目 MUST 优先使用现有库、标准库和本仓库 helper。新增依赖必须说明属于 Android 原生侧还是 Open WebUI 前端侧，并通过 `android/gradle/libs.versions.toml` 或 npm/lockfile 的正常流程维护。提交中 MUST NOT 包含 secrets、本机配置、APK/AAB、临时构建产物、`node_modules`、`.svelte-kit`、Open WebUI build 输出或无关缓存。为验证数据库迁移而由 Room 导出并经审查的 `android/app/schemas/` 版本历史 MUST 随对应 migration 提交；其他临时 schema 输出不得提交。

Rationale: 依赖和生成物会扩大维护面、供应链风险和审查成本。

### VI. 高风险变更必须先有 ExecPlan

Room 迁移、权限或网络安全配置、凭据/Cookie 存储、生产配置、构建或依赖升级、Open WebUI 与 Android 桥接、第三方插件身份/runtime/origin/存储/校园代理、Agent 工具安全边界、数据删除和架构边界变化 MUST 先编写可执行、可恢复、持续更新的 ExecPlan。ExecPlan MUST 明确目标、范围、验证命令、失败处理和回滚策略，再进入实现。

Rationale: 高风险变更需要可交接的上下文和回滚路径，不能只依赖聊天记录或临时判断。

### VII. 插件平台契约化安全基线不得降级

Manifest v3 / contract_v1 是第三方插件唯一有效的运行与兼容基线。新客户端 MUST 拒绝安装、更新和运行 Manifest v1/v2 以及 P0-A v3，MUST 拒绝 `allowed_origins`、`permissions`、`runtime_version`、`min_runtime_version`、`bridge_origins` 和内嵌 marketplace 元数据，且 MUST NOT 恢复 `identity.get_credentials`、`identity.credentials.read` 或通用原生 `app.http_request`。旧包和数据只能保留在无桥、无网络的救援环境中，除非用户明确删除；P0-A v3 仅可在 publisher subject、plugin ID 与数据版本全部匹配时原位升级为 contract_v1。

原生桥 MUST 仅注入由 plugin ID 与不可变 publisher subject 决定的稳定本地 origin，且消息必须来自 main frame 并精确匹配 source origin。桥接 origin 是宿主固定为 self 的不变量，插件 Manifest 不得声明或扩展它；远程 frame 永远无桥。远程来源 MUST 按 connect、media、frame、navigation 分离。校园会话只能通过宿主登记的只读 `campus.request` registry 使用，Cookie、认证头和明文凭据不得暴露给插件。

插件公开 API MUST 通过 SDK 的命名空间和 protocol v2 调用 Capability；底层 transport 不得作为公共 API 暴露。Capability ID、版本、方法、请求/响应 Schema、权限、逐次确认、配额、超时、错误、平台支持范围和稳定性 MUST 以单一 JSON Contract Registry 为权威来源。optional Capability 默认关闭；所有改变校园或宿主状态的 Command Capability MUST 逐次确认并使用请求摘要绑定的 idempotency key。

插件公网访问 MUST 使用无 Cookie、无宿主认证器的隔离客户端，并在初始请求、每次 DNS 解析和每次重定向重新阻止校园、私网、回环和链路本地目标。插件持久化、更新和删除 MUST 使用 publisher+plugin 隔离、加密且受配额约束的数据空间，并保持迁移、快照、回滚、增量授权和失败清理可恢复。任何文档、旧 feature spec、兼容代码或迁移期 API 都不得被用来削弱这些约束；需要改变本基线时 MUST 先修订 constitution、维护新的安全 ExecPlan 并通过对应阻塞验证。

Rationale: 插件代码不受宿主控制，稳定身份、最小桥接、分域联网、事务数据和只读校园代理必须作为统一零信任边界，而不能由旧兼容策略逐项回退。

## Development Workflow

- 开始修改前 MUST 检查 `git status --short`，并保护用户已有改动。
- 需求或实现存在不确定信息时 MUST 标为 `TODO: confirm`，不得把猜测写成事实。
- 修改范围 MUST 贴近任务，不顺手重构无关模块。
- 验证命令 MUST 按改动范围选择：Android 业务或解析改动运行 `Set-Location android; .\gradlew.bat test`；打包、资源或 WebView 资产相关改动运行 `Set-Location android; .\gradlew.bat assembleDebug`；Open WebUI local-first 或前端逻辑改动运行 `Set-Location android\open-webui; npm run test:frontend -- --run`。
- 插件平台或 Manifest 变更 MUST 运行 `web/platform` 的 typecheck、unit、integration、e2e，运行共享 Manifest lint，并编译或执行适用的 Android WebView instrumentation；因环境缺失而未执行的阻塞验证 MUST 交由 CI 且在汇报中明确记录。
- 修改插件相关规范时 MUST 检索 v1/v2、`allowed_origins`、明文凭据桥和通用 HTTP 桥等旧契约；历史文档只能保留带有明确 superseded 标记的非规范说明。
- `npm run lint:frontend` 和 `npm run format` 会写入文件，只能在明确需要格式化或自动修复前端文件时运行。
- 最终汇报 MUST 说明文件变更、主要规则、TODO/不确定项和验证结果；未运行的验证必须说明原因。

## Governance

本 constitution 是项目原则的权威来源。`AGENTS.md`、`PLANS.md`、README 和 Spec Kit 模板可以提供执行细则；如出现冲突，以本文件为准，并同步修正文档。

插件安全策略的规范优先级依次为：

1. 本 constitution，尤其是原则 VII；
2. `plugin-tooling/contracts/capability-contracts.json`、`docs/third-party-services.md` 与生成且字节一致的 Manifest/marketplace schema；
3. 当前有效的 `docs/plugin-runtime-contract-p0-p1-execplan.md`、实现和阻塞测试；
4. 带有 superseded/legacy 标记的旧 spec、plan、API 和迁移说明，仅可用于历史追踪或数据救援。

低优先级材料与高优先级规则冲突时，冲突部分自动失效。旧文档不得授权重新启用 v1/v2 运行时、聚合 origin、远程桥、明文凭据读取或通用原生 HTTP。

修订流程：

1. 提交 constitution 修改时 MUST 更新顶部 Sync Impact Report。
2. 原则或治理规则变化时 MUST 同步检查 `.specify/templates/`、`AGENTS.md`、`PLANS.md` 和 README 中的相关引用。
3. 破坏性治理调整、原则删除或原则重新定义升级 MAJOR；新增原则或实质扩展升级 MINOR；措辞澄清和非语义修正升级 PATCH。
4. 任何 feature spec、implementation plan 或任务拆分 MUST 通过模板中的 Constitution Check；未通过项必须先调整设计或写入明确的 `TODO: confirm` 阻塞说明。

**Version**: 1.2.0 | **Ratified**: 2026-06-11 | **Last Amended**: 2026-07-29
