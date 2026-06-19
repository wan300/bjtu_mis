<!--
Sync Impact Report
Version change: none -> 1.0.0
Modified principles: none; initial adoption
Added sections: Core Principles; Development Workflow; Governance
Removed sections: none
Templates requiring updates: .specify/templates/spec-template.md (updated), .specify/templates/plan-template.md (updated), .specify/templates/tasks-template.md (updated)
Runtime guidance requiring updates: AGENTS.md (updated), PLANS.md (updated), README.md (updated), android/README.md (updated)
Deferred follow-ups: none
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

### IV. 外部系统与 Agent 工具变更必须可测试

parser 变更 MUST 使用 fixture 覆盖成功、缺字段和上游结构变化。provider/repository 变更 SHOULD 使用 MockWebServer、fixture 或 fake/store 模式，不依赖真实校园账号或线上服务。Agent 文件、归档、文档、代码、邮件和结果打包工具变更 MUST 覆盖 workspace 边界、路径穿越、大小限制、条目数量、确认流程和错误反馈。Open WebUI local-first、handoff、工具参数或流式输出变更 MUST 运行对应 Vitest。

Rationale: 上游页面和工具输入不受本项目控制，测试必须固定风险边界并防止回归。

### V. 最小依赖与干净提交

项目 MUST 优先使用现有库、标准库和本仓库 helper。新增依赖必须说明属于 Android 原生侧还是 Open WebUI 前端侧，并通过 `android/gradle/libs.versions.toml` 或 npm/lockfile 的正常流程维护。提交中 MUST NOT 包含 secrets、本机配置、APK/AAB、构建产物、Room schema、`node_modules`、`.svelte-kit`、Open WebUI build 输出或无关缓存。

Rationale: 依赖和生成物会扩大维护面、供应链风险和审查成本。

### VI. 高风险变更必须先有 ExecPlan

Room 迁移、权限或网络安全配置、凭据/Cookie 存储、生产配置、构建或依赖升级、Open WebUI 与 Android 桥接、Agent 工具安全边界、数据删除和架构边界变化 MUST 先编写可执行、可恢复、持续更新的 ExecPlan。ExecPlan MUST 明确目标、范围、验证命令、失败处理和回滚策略，再进入实现。

Rationale: 高风险变更需要可交接的上下文和回滚路径，不能只依赖聊天记录或临时判断。

## Development Workflow

- 开始修改前 MUST 检查 `git status --short`，并保护用户已有改动。
- 需求或实现存在不确定信息时 MUST 标为 `TODO: confirm`，不得把猜测写成事实。
- 修改范围 MUST 贴近任务，不顺手重构无关模块。
- 验证命令 MUST 按改动范围选择：Android 业务或解析改动运行 `Set-Location android; .\gradlew.bat test`；打包、资源或 WebView 资产相关改动运行 `Set-Location android; .\gradlew.bat assembleDebug`；Open WebUI local-first 或前端逻辑改动运行 `Set-Location android\open-webui; npm run test:frontend -- --run`。
- `npm run lint:frontend` 和 `npm run format` 会写入文件，只能在明确需要格式化或自动修复前端文件时运行。
- 最终汇报 MUST 说明文件变更、主要规则、TODO/不确定项和验证结果；未运行的验证必须说明原因。

## Governance

本 constitution 是项目原则的权威来源。`AGENTS.md`、`PLANS.md`、README 和 Spec Kit 模板可以提供执行细则；如出现冲突，以本文件为准，并同步修正文档。

修订流程：

1. 提交 constitution 修改时 MUST 更新顶部 Sync Impact Report。
2. 原则或治理规则变化时 MUST 同步检查 `.specify/templates/`、`AGENTS.md`、`PLANS.md` 和 README 中的相关引用。
3. 破坏性治理调整、原则删除或原则重新定义升级 MAJOR；新增原则或实质扩展升级 MINOR；措辞澄清和非语义修正升级 PATCH。
4. 任何 feature spec、implementation plan 或任务拆分 MUST 通过模板中的 Constitution Check；未通过项必须先调整设计或写入明确的 `TODO: confirm` 阻塞说明。

**Version**: 1.0.0 | **Ratified**: 2026-06-11 | **Last Amended**: 2026-06-11
