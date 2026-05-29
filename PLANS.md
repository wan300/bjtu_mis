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
