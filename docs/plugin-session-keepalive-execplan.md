# ExecPlan: 插件 MIS 会话保活租约

## 目标和范围

用户已选择方案 B：新增 `android.session.keepAlive@1`，首次/增量授权后插件可主动申请、续租、查询和释放有限时长租约。保活维持宿主 MIS 会话，并不保证 Android 永不杀进程，也不提供通用后台任务或凭据访问。保护已有 Agent、抢课租约。只使用现有 Kotlin、加密存储、protocol v2 和生成工具，不引入依赖或 Room migration。

初始工作区只有用户已有未跟踪的 `.codex/`、`tmp_media3_ui/`；本任务不触碰它们。权威来源为 `.specify/memory/constitution.md`、contract registry、Manifest v3；不改变旧 runtime 禁用、main-frame/source-origin、分域联网、校园只读代理和 KV 影子迁移边界。

## 决策

- constitution 3.1.0 严格加入这一持久授权例外。Manifest 使用真实的 `capabilities.required/optional` 结构；optional 默认关闭，显式授权后才可调用。
- `acquire` 只创建新租约；`renew` 显式续租。单租约自创建最多 60 分钟；1–60 分钟申请，每插件最多 2 租约，每分钟最多 6 次 acquire/renew，滚动一小时最多 60 租约分钟（并行租约分别计费）。release/status 不因开启配额用尽而失败。
- 内置任务与插件统一 controller 持有租约，owner 由宿主确定；插件永远不能调用全局 stop 或释放内置/其他发布者租约。
- acquire/renew 必须是前台 runtime 且 Activity 已 resumed；授权不代替系统条件。后台可 release/getStatus。持久记录在进程重建后重新验证 publisher、声明、授权、enabled/review 和版本，再在合法前台时恢复，不延长到期时间。设备重启/时钟倒退使租约失效；Android 系统 FGS 限额独立于应用 5h30 上限。
- 使用加密原子快照持久化插件租约、预算和幂等摘要；不存 purpose 自由文本、Cookie、认证头或上游错误。API 不接收 purpose，自始至终使用宿主固定模板，通知使用宿主固定文案。幂等回执是执行时快照，当前状态通过 getStatus 查询；重复请求不重启已结束租约。
- lease 到期采用独立短周期清理，不能等 20 分钟网络轮询。通知 stop、退出账号、超时立即清理所有租约；插件更新/回滚/撤销/禁用/删除清理该插件租约，快照不能恢复被用户停止的任务。
- 租约为后台 WebView 提供有期限的运行理由，仍与订阅共用最多 4 个 runtime 的 supervisor。事件前台优先，结束后释放该理由，不影响其他有效订阅。

## 里程碑与文件

1. [ ] 修订 constitution、AGENTS、相关模板/开发文档；本计划先于实现。
2. [ ] sync 下 controller/加密快照及 JVM 测试；Service、Agent、抢课统一调用，处理启动失败/超时/退出。
3. [ ] Registry、SDK、mock host、Provider、AppContainer；运行生成器同步所有产物，runtimeFloor 提升并兼容旧能力。
4. [ ] supervisor 恢复、前台 gate、事件、repository 清理和通知停止。
5. [ ] 契约、Android JVM、前端、平台和 instrumentation 验证；更新说明与实际结果。

## 验证

- plugin-tooling：`npm run generate:check`、`npm run typecheck`、`npm test`、`npm run pack:check`。
- Android：`gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；API 26/35 `connectedDebugAndroidTest`。控制器测试覆盖跨 owner、幂等冲突/重放、配额、到期、并发租约、恢复、时钟变化、启动失败、撤销竞态、用户停止与内部任务共存。
- Open WebUI：`npm run test:frontend -- --run`。
- 平台：typecheck、unit、integration、e2e；无 PostgreSQL 时记录跳过，不视作真实数据库验证。
- Manifest lint 覆盖模板/示例，生成的 docs/web schema 字节相同；WebView 测试保持远程/子 frame 无桥。
- API 26/35 真机/模拟器覆盖通知、前台 gate、拒绝启动、停止、恢复与 runtime 归属；环境缺失记录并交既有阻塞 CI。

## 风险和回滚

FGS 启动可能被 Android 拒绝，必须撤回新活动租约且不能返回 running；MIS 短暂断网标 degraded，认证不可恢复结束插件租约，错误仅返回枚举。加密记录损坏安全关闭；清理持久化失败需保留可重试状态并禁止恢复。

回滚前停止所有插件保活并撤销 capability，部署旧客户端会拒绝声明新能力的插件（runtimeFloor/未知 capability），不迁移 Room。回退本任务代码、Registry 和生成物；保留插件 KV/Blob 与内置任务行为。不得将新 capability 强制转为旧桥，不恢复已停止租约。构建产物/本机配置不提交。

## 进度

- 2026-09-09：核对现有服务/契约；服务使用内存 token、20 分钟网络轮询、5h30 上限，需新增独立到期与持久 owner 管理。本计划由用户选择方案 B 授权执行。
- TODO: confirm：本机 API 26/35 设备可用性、通知/FGS 真实系统验证结果。

- 2026-09-09：新增 Controller、Android 加密 AtomicFile 快照、Provider、runtime 3 capability、SDK 和 Mock Host；保留旧能力 floor 2。lease 验证恢复与启动独立，幂等回执不复活已结束租约。
- 校验：Debug JVM（含新增 9 项租约测试）通过；instrumentation Kotlin 编译通过；工具链 37 项测试/类型检查/generate:check/pack:check 通过；前端 11 文件 96 项测试通过；平台 typecheck/unit/e2e 通过，integration 数据库环境待记录。
- 发现：本机设备 127.0.0.1:16384 为 API 32，未连接 API 26/35。资产目录存在其他构建写入，引发一次 syncOpenWebUiAssets 删除失败；此后按顺序构建，未修改生成资产。
- 并行外部变更：android/README.md 的“打包时生成文件缺失”段落在任务进行期间出现；非本任务修改，保留。
- 2026-09-09 发布审查：修复管理页单独停止插件时未清理保活租约、随后可能被 reconcile 重启的问题；stopServiceRuntime 在停止 runtime 前撤销该 publisher+plugin 的租约，新增跨 owner/内置任务隔离及持久恢复回归。需要重建签名 APK。
