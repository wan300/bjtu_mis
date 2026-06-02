# ExecPlan 模板

`ExecPlan` 是复杂工作的自包含、持续更新的执行计划。它必须让新的 Codex 会话或工程师在没有聊天上下文的情况下继续工作，并聚焦于交付可运行、已验证的行为，而不是只列代码编辑清单。

适用场景：复杂功能、风险重构、Room 数据迁移、构建或依赖升级、Open WebUI 与 Android 桥接变化、权限/安全敏感改动、架构边界变化，或需求仍有歧义的任务。

使用规则：

- 计划必须随发现、决策、失败验证和进度变化持续更新。
- 所有歧义必须在计划里明确解决；不能解决的写成 `TODO: confirm` 并说明阻塞影响。
- 使用苏格拉底式提问来澄清目标、约束和设计；不要假设任何未明确说明的事实。
- 每个里程碑都必须包含预期可观察行为和验证命令。
- 默认验证命令按改动范围选择：Android 改动跑 `Set-Location android; .\gradlew.bat test`；构建/资源/打包相关跑 `Set-Location android; .\gradlew.bat assembleDebug`；Open WebUI 改动跑 `Set-Location android\open-webui; npm run test:frontend -- --run`；类型检查可跑 `npm run check`，但当前 Open WebUI 存在既有类型诊断基线失败，需记录。
- 不要把执行计划当作一次性草稿；实现过程中要维护它。

## ExecPlan: <标题>

### 1. Title

一句话标题，包含目标模块或用户可见能力。

示例：`ExecPlan: 修复 VE 作业状态解析并同步 UI 展示`

### 2. Purpose and user-visible outcome

说明为什么要做，以及用户完成后能看到什么变化。

- 用户场景：
- 当前问题：
- 完成后的可观察行为：
- 成功标准：

### 3. Repository context

写清楚接手者必须知道的仓库事实。引用实际路径，但不要依赖聊天记录。

- Android 工程根目录：`android/`。
- 相关 Android 代码：
- 相关 Open WebUI 代码：
- 相关测试：
- 相关 fixture：
- 已知生成文件或不可提交文件：

### 4. Constraints and non-goals

列出必须遵守的边界和明确不做的事。

- 不修改：
- 不引入：
- 不提交：
- 安全/隐私限制：
- 兼容性要求：
- 非目标：

### 5. Research notes

记录已经检查过的事实、命令和结论。发现新事实时追加，不覆盖重要历史。

- `git status --short`：
- README/文档依据：
- Gradle/npm 配置依据：
- 源码结构依据：
- 测试结构依据：
- 未确认事项：

### 6. Proposed design

描述要实现的设计，而不是只列文件。说明数据流、接口边界、错误处理和用户反馈。

- Android 数据流：
- UI 行为：
- Open WebUI/Capacitor 桥接：
- Room/缓存/迁移影响：
- 错误和空状态：
- 与既有模式保持一致的点：

### 7. Files and components to change

列出预计改动的文件或组件。文件清单应足够具体，但不要扩大范围。

- `path/to/file`：改动目的。
- `path/to/test`：新增或更新验证。
- `path/to/fixture`：新增或更新测试样本。
- 生成文件处理：说明是否会产生，是否应忽略或提交。

### 8. Milestones

每个里程碑都要包含可观察行为和验证命令。

#### Milestone 1: <名称>

- 目标：
- 预期可观察行为：
- 验证命令：
- 完成状态：pending

#### Milestone 2: <名称>

- 目标：
- 预期可观察行为：
- 验证命令：
- 完成状态：pending

### 9. Step-by-step implementation plan

写成可以逐步执行的清单。每一步都应说明改什么、为什么改、如何确认。

1. 检查当前 worktree，记录用户已有改动，不回退无关文件。
2. 修改数据模型或解析逻辑，并保持与 `AppJson`、`ModuleEnvelope`、现有异常模式一致。
3. 修改 repository/provider 编排，确保 UI 不直接访问外部服务。
4. 修改 Compose UI 或 Open WebUI local-first 逻辑，复用现有状态和组件模式。
5. 添加或更新单元测试与 fixture。
6. 运行验证命令，记录通过、失败或已知基线问题。
7. 检查 `git diff`，确认没有生成文件、密钥、本机配置或无关源码改动进入本次变更。

根据具体任务替换以上步骤；不要保留不适用的步骤。

### 10. Validation plan

按风险列出必须运行的验证，并写出接受标准。

- Android 单元测试：`Set-Location android; .\gradlew.bat test`
  - 接受标准：
- Android 打包验证：`Set-Location android; .\gradlew.bat assembleDebug`
  - 接受标准：
- Open WebUI 前端测试：`Set-Location android\open-webui; npm run test:frontend -- --run`
  - 接受标准：
- Open WebUI 类型检查：`Set-Location android\open-webui; npm run check`
  - 接受标准或已知失败说明：
- 手动验证：
  - 设备/模拟器：
  - 网络/账号要求：
  - 用户可见路径：

### 11. Progress log

持续追加，使用绝对时间或清晰顺序，不删除有用历史。

- YYYY-MM-DD HH:mm：创建计划，记录初始目标和约束。
- YYYY-MM-DD HH:mm：完成 <步骤>，观察到 <结果>。
- YYYY-MM-DD HH:mm：验证 <命令>，结果 <通过/失败>，失败原因 <原因>。

### 12. Decision log

记录会影响实现或维护的决策。

- Decision：
  - Context：
  - Options considered：
  - Chosen approach：
  - Consequences：

### 13. Risks and rollback plan

说明主要风险、检测方式和回滚策略。

- 风险：
- 影响：
- 如何发现：
- 缓解措施：
- 回滚步骤：
- 数据迁移回滚：TODO: confirm，如果任务涉及 Room 迁移必须补全。

### 14. Completion checklist

完成前逐项确认。

- [ ] 计划已包含目标、上下文、约束、设计、文件、里程碑、步骤、验证、风险和回滚。
- [ ] 所有歧义已解决，未解决项标为 `TODO: confirm` 并说明影响。
- [ ] 实现只触碰计划范围内文件。
- [ ] 已保护用户现有 worktree 改动，没有回退或覆盖无关源码。
- [ ] 已添加或更新必要测试和 fixture。
- [ ] 已运行适当验证命令，并记录结果。
- [ ] 已检查 `git status --short` 和 `git diff`。
- [ ] 没有提交 secrets、本机配置、构建产物、APK/AAB、`node_modules`、Open WebUI build、Room schema 或缓存。
- [ ] 最终汇报包含变更文件、主要行为、验证结果、已知失败和剩余 TODO。

## ExecPlan: 评教模块实时读取与批量确认提交

### 1. Title

新增 AA 评教模块，支持实时列表、多选预填、批量确认提交。

### 2. Purpose and user-visible outcome

- 用户场景：学生在 App 内打开“评教”，读取 AA 教学支撑平台待评课程，选择一门或多门课程后批量预览并提交。
- 当前问题：仓库尚无评教模块，录制流程显示评教入口只存在于 AA Web 页面。
- 完成后的可观察行为：服务页出现“评教”入口；页面可刷新待评课程；支持多选/全选；批量预览自动填入最正向单选项和默认主观评价；用户显式确认后逐门提交并显示结果。
- 成功标准：提交前必须显示确认界面；不会静默提交；单门失败不阻断剩余课程；不写入 Room 快照或全量同步。

### 3. Repository context

- Android 工程根目录：`android/`。
- 相关 Android 代码：`model/Models.kt`、`data/parser/AaParsers.kt`、`data/provider/AaProvider.kt`、`data/repository/Repositories.kt`、`ui/BjtuMisApp.kt`、`ui/screens/OverviewScreen.kt`。
- 相关测试：`android/app/src/test/kotlin/cn/edu/bjtu/mis/data/parser/ParserTest.kt`。
- 相关 fixture：`android/app/src/test/resources/fixtures/`。
- 录制证据：`captures/logs/evaluation_20260602_155055/`，仅本地分析使用，不提交。
- 已知用户 worktree 改动：`web/assets/js/site.js`、`web/index.html`、`.playwright-mcp/page-2026-05-30T17-49-45-218Z.yml`，本计划不触碰。

### 4. Constraints and non-goals

- 不修改：Open WebUI、Room schema、全量同步、Web 目录现有用户改动。
- 不引入：新依赖。
- 不提交：录制日志、真实账号信息、Cookie、token、个人信息、构建产物。
- 安全限制：评教提交必须由用户在 App 中显式确认；不得后台静默自动提交。
- 非目标：不做评教数据长期缓存，不接入 Agent 自动提交。

### 5. Research notes

- `git status --short`：当前已有与本任务无关的 `web/` 改动和 Playwright 记录文件。
- 录制接口：`GET /teaching_assessment/stu/list/`，`GET /teaching_assessment/stu/{id}/update/`，`POST /teaching_assessment/stu/{id}/update/`。
- 录制提交体：Django formset，包含 `select-*`、`comment-*`、`multi-*`、`csrfmiddlewaretoken`、`refer`。
- 单选值动态生成，必须从表单页解析 radio label 到 value；不能写死录制中的数字。
- 成功判定：POST 后回到列表页，目标课程按钮从“评教”变“查看”，页面消息包含“评教成功”。

### 6. Proposed design

- Android 数据流：`ModuleRepository` 通过 `SessionManager.withAuthenticatedClient` 创建 `AaProvider`，实时读取列表、读取表单和提交，不写入 `SyncRepository` 快照。
- Parser：新增评教列表解析、表单解析、默认预填选择逻辑；解析隐藏字段和每题 radio/textarea。
- Provider：新增 `fetchTeachingAssessmentList`、`fetchTeachingAssessmentForm`、`submitTeachingAssessment`，复用 AA 登录态检查和表单 POST。
- UI：新增 `TeachingAssessmentScreen`，列表选择、多选/全选、预览加载、统一主观评价、确认提交、逐门进度和结果汇总。
- 错误和空状态：无待评课程显示空状态；读取表单失败显示失败项；提交失败不阻断后续课程。
- 与既有模式一致：复用 `InfoCard`、`SectionTitle`、`LoadState`、`rememberCoroutineScope`、`runCatching`。

### 7. Files and components to change

- `android/app/src/main/java/cn/edu/bjtu/mis/model/Models.kt`：新增评教数据模型和模块 key。
- `android/app/src/main/java/cn/edu/bjtu/mis/data/parser/AaParsers.kt`：新增评教 HTML 解析与默认选项逻辑。
- `android/app/src/main/java/cn/edu/bjtu/mis/data/provider/AaProvider.kt`：新增评教 AA 请求和提交。
- `android/app/src/main/java/cn/edu/bjtu/mis/data/repository/Repositories.kt`：新增实时评教 repository 方法。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/BjtuMisApp.kt`：新增路由分发。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/OverviewScreen.kt`：新增服务入口。
- `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/TeachingAssessmentScreen.kt`：新增 Compose 页面。
- `android/app/src/test/kotlin/cn/edu/bjtu/mis/data/parser/ParserTest.kt` 与 fixtures：新增解析测试。

### 8. Milestones

#### Milestone 1: 数据层和解析

- 目标：从 AA HTML 中解析待评课程、评教表单和默认预填答案。
- 预期可观察行为：单元测试能验证列表和表单解析结果。
- 验证命令：`Set-Location android; .\gradlew.bat test --tests cn.edu.bjtu.mis.data.parser.ParserTest`
- 完成状态：completed。实际验证命令使用 `.\gradlew.bat :app:testDebugUnitTest --tests cn.edu.bjtu.mis.data.parser.ParserTest --no-daemon --console=plain`，结果通过。

#### Milestone 2: UI 与安全提交

- 目标：服务页可进入评教模块，用户可选择课程、预览预填、确认后逐门提交。
- 预期可观察行为：代码层不存在未确认提交入口，提交只由确认按钮触发。
- 验证命令：`Set-Location android; .\gradlew.bat test`
- 完成状态：completed。`.\gradlew.bat test --no-daemon --console=plain` 已通过。

### 9. Step-by-step implementation plan

1. 检查当前 worktree，记录无关用户改动，不回退。
2. 新增模型和解析函数，使用合成 fixture 覆盖列表、表单、默认正向选项和成功页状态。
3. 在 `AaProvider` 中新增实时读取与提交方法，保持 AA host 校验和登录态错误处理。
4. 在 `ModuleRepository` 中增加不缓存的评教方法。
5. 新增 Compose 页面和服务入口，保证提交必须经过批量预览确认。
6. 运行目标测试和必要的 Android 单元测试，记录结果。
7. 检查 `git status --short` 与 diff，确认没有录制日志、真实个人信息或无关文件进入本次变更。

### 10. Validation plan

- Android parser 测试：`Set-Location android; .\gradlew.bat test --tests cn.edu.bjtu.mis.data.parser.ParserTest`
  - 接受标准：新增评教解析测试通过，既有 parser 测试不回退。
- Android 单元测试：`Set-Location android; .\gradlew.bat test`
  - 接受标准：测试通过，或失败明确为与本次无关的既有环境问题。
- 手动验证：需要可访问 BJTU AA 的网络和有效账号；本轮无法真实重复提交已评课程时，以录制接口证据和 parser/provider 逻辑作为实现依据。

### 11. Progress log

- 2026-06-02：根据录制日志确认 AA 评教流程和提交字段，创建执行计划。
- 2026-06-02：新增评教模型、AA 解析、Provider、Repository、Compose 页面、服务入口和 parser fixture 测试。
- 2026-06-02：验证 `:app:testDebugUnitTest --tests cn.edu.bjtu.mis.data.parser.ParserTest` 通过。
- 2026-06-02：验证 `test` 通过。

### 12. Decision log

- Decision：评教不写入 Room 快照。
  - Context：用户确认评教短期且强实时，缓存价值低。
  - Chosen approach：仅通过 `SessionManager` 实时读取和提交。
  - Consequences：全量同步不包含评教，用户每次打开模块刷新实时状态。
- Decision：批量提交必须用户确认。
  - Context：评教属于高风险用户操作。
  - Chosen approach：列表选择后进入预览，确认按钮触发逐门提交。
  - Consequences：不会静默提交；提交失败可汇总和重试。

### 13. Risks and rollback plan

- 风险：AA 表单结构变化导致解析失败。
  - 影响：无法预填或提交。
  - 如何发现：parser 测试失败、UI 显示表单读取失败。
  - 缓解措施：解析隐藏字段和 label/value，不写死题目数量或 value。
  - 回滚步骤：移除新增模块入口和相关 provider/parser/model/UI 改动。
- 风险：用户误批量提交。
  - 影响：评教不可逆或难以修改，TODO: confirm。
  - 缓解措施：必须显式确认，预览课程和预填内容。
  - 数据迁移回滚：不涉及 Room 迁移。

### 14. Completion checklist

- [x] 新增评教模型、解析器、Provider、Repository 和 UI。
- [x] 服务页出现评教入口。
- [x] 列表支持多选和全选。
- [x] 批量预览自动按正向单选和默认主观评价预填。
- [x] 用户确认后逐门提交，失败不阻断后续课程。
- [x] 不写入 Room 快照或全量同步。
- [x] 新增 parser 测试和合成 fixture，无真实个人信息。
- [x] 运行并记录验证命令。
