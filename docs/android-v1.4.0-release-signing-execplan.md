# ExecPlan: Android v1.4.0 正式版本与发布签名

## 1. Purpose and user-visible outcome

将 Android 正式版本升级为 `v1.4.0`，并让 `assembleRelease` 使用本机安全提供的正式签名密钥。最终目标是生成可安装、可由 Android 签名工具验证，且能从 `v1.3.1` 覆盖升级的 Release APK。

成功标准：

- `versionName` 为 `v1.4.0`，`versionCode` 为 `7`。
- Release 构建只从未提交的本机属性文件或环境变量读取 keystore 路径、alias 和口令。
- 缺少签名配置时不静默生成可误上传的未签名正式包。
- 生成的 APK 通过 `apksigner verify --verbose --print-certs`。
- 新 APK 的签名证书与 GitHub Release `v1.3.1` APK 一致；若无法取得旧密钥，则停止并标记 `TODO: confirm`，不得生成新的正式发布身份。

## 2. Repository context

- Android 构建配置：`android/app/build.gradle.kts`。
- 本机配置忽略规则：`.gitignore` 已忽略 `android/key.properties`、`android/keystore.properties`、`*.jks` 和 `*.keystore`。
- 修改前 Release 构建未声明 `signingConfigs.release`。
- 修改前版本为 `versionName = "v1.3.1"`、`versionCode = 6`。
- 工作区存在大量用户已有 UI、网站和部署改动；本任务不得回退或重写这些改动。

## 3. Constraints and non-goals

- 不提交 keystore、alias、密码、token 或其他私密数据。
- 不在终端输出属性值或口令。
- 不创建新发布密钥来替代 `v1.3.1` 密钥，除非用户明确确认接受无法覆盖升级的后果。
- 不修改 applicationId `cn.edu.bjtu.mis`。
- 不提交 APK/AAB、Open WebUI build 输出或其他生成物。
- 本任务不发布 GitHub Release，只准备并验证本地正式包。

## 4. Implemented design

- `android/app/build.gradle.kts` 读取被忽略的 `android/release-signing.properties`，并允许等价环境变量作为 CI/临时构建入口。
- 同时识别 Android Studio “Generate Signed App Bundle or APK”向导提供的 `android.injected.signing.*` Gradle 属性。
- 仅当四项必要签名配置完整且 keystore 文件存在时创建 Release signing config。
- `release` build type 绑定该 signing config。
- 直接或间接执行 Release 打包任务而未配置签名时，构建给出明确错误并停止。
- 提供不含秘密的 `android/release-signing.properties.example`，记录必要键和路径解析规则。
- 最终使用旧版与新版 APK 的证书 SHA-256 摘要做升级兼容性核验。

## 5. Files changed

- `android/app/build.gradle.kts`：版本号与 Release signing config。
- `android/release-signing.properties.example`：本机配置模板，不含真实值。
- `.gitignore`：忽略真实 `android/release-signing.properties`。
- `android/README.md`：补充 v1.4.0 正式构建与签名配置说明。
- 本文件：持续记录发现、决策、验证和回滚。

## 6. Milestones

### Milestone 1: 识别既有发布身份

- 已下载并校验 GitHub Release `v1.3.1` APK。
- `apksigner` 确认 APK 签名有效，签名证书 SHA-256 为 `6808eb315b7619f375ed7a58abe24bd53125426203ceaf755d71290a46cf7af8`。
- 在仓库、D 盘及当前用户常用目录中查找本机 keystore；仅找到另一个项目的 PKCS#12 文件，其证书摘要不匹配，未使用。
- Git 历史与 `v1.3.1` 标签中没有 signing config、keystore 或签名属性文件可供恢复。
- 完成状态：completed；原密钥仍需用户提供。

### Milestone 2: 配置 v1.4.0 Release 签名

- 已将版本更新为 `v1.4.0` / `7`。
- 已接入 ignored 属性文件和环境变量两种安全配置入口。
- 已实现 Release 任务缺少配置时明确失败。
- 完成状态：completed。

### Milestone 3: 构建与签名核验

- Gradle 配置阶段已验证可用。
- 缺少签名时，直接 `assembleRelease` 与间接 `build --dry-run` 均按预期失败。
- Android 单元测试两次长时间无输出，终止对应 Gradle daemon，尚未得到测试结果。
- 2026-07-29 修复 Release 任务前缀匹配误拦截 `packageReleaseResources` 后，完整 `test` 已通过，同时确认 `assembleRelease --dry-run` 仍会在缺少签名时停止。
- 正式包构建、新版 APK 签名验证和新旧证书对比，等待原始 keystore。
- 完成状态：blocked，原因是缺少 `v1.3.1` 原始发布密钥。

## 7. Validation results

- `Set-Location android; .\gradlew.bat help --offline --no-daemon`：通过，Gradle 配置成功。
- `Set-Location android; .\gradlew.bat assembleRelease --offline --no-daemon`：按预期失败，明确提示缺少 Release 签名配置。
- `Set-Location android; .\gradlew.bat build --dry-run --offline --no-daemon`：按预期失败，证明间接 Release 任务也受保护。
- 使用四个 `android.injected.signing.*` 测试参数执行 `assembleRelease --dry-run --offline --no-daemon`：通过，确认 Android Studio 签名向导不再被提前拦截。
- 不带任何签名参数再次执行 `assembleRelease --dry-run --offline --no-daemon`：按预期失败，确认命令行保护仍然生效。
- `Set-Location android; .\gradlew.bat test --no-daemon`：约 90 秒无输出后终止，未取得结果。
- `Set-Location android; .\gradlew.bat test --offline --no-daemon`：约 150 秒无输出后终止，未取得结果。
- `Set-Location android; .\gradlew.bat test --no-daemon --console=plain`：修复任务匹配后通过，包含 debug 与 release JVM 单元测试。
- `Set-Location android; .\gradlew.bat assembleDebug --no-daemon --console=plain`：通过。
- `Set-Location android; .\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --console=plain`：通过。
- `Set-Location android; .\gradlew.bat assembleRelease --dry-run --no-daemon --console=plain`：按预期失败，仍明确提示缺少 Release 签名配置。
- `apksigner verify --verbose --print-certs <v1.3.1.apk>`：通过；v2 签名有效，证书为 RSA 2048，主题 `CN=w`。
- `git diff --check`：通过；仅报告工作区既有文件的 LF/CRLF 转换警告。
- `git status --short`：未发现 keystore、真实签名属性文件或 APK 被纳入工作区变更。

最终接受标准尚未全部满足：必须在获得原始密钥后完成 `assembleRelease`、新版 `apksigner` 验证和新旧证书摘要一致性检查。

## 8. Risks and rollback

- 风险：使用新密钥会导致现有用户无法覆盖升级。
  - 缓解：必须比对 `v1.3.1` 与新包证书摘要；不一致即停止。
- 风险：私密信息被提交或打印。
  - 缓解：真实属性文件与 keystore 保持 ignored；状态检查只输出文件名，不输出内容。
- 风险：Release 构建意外回退为 debug/unsigned 签名。
  - 缓解：Release 任务缺少完整配置时失败，并用 `apksigner` 验证最终产物。
- 回滚：仅撤销本计划、本机签名配置模板、`.gitignore` 条目、README 说明以及 `build.gradle.kts` 中版本/签名相关行；不触碰用户其他改动。

## 9. Progress log

- 2026-07-28：检查工作区、constitution、PLANS 和 Android Gradle 配置；确认修改前没有 Release signing config，版本为 `v1.3.1` / `6`。
- 2026-07-28：从 GitHub Release 获取 `v1.3.1` APK，SHA-256 为 `6AACF00A89F1DCC957C42F5FB7DCD3E7D6148C861C0C907E969B782E753FB032`。
- 2026-07-28：验证旧 APK 的 v2 签名有效，记录证书 SHA-256 `6808eb315b7619f375ed7a58abe24bd53125426203ceaf755d71290a46cf7af8`。
- 2026-07-28：查找到的唯一外部 keystore 属于另一项目，证书 SHA-256 为 `8ec32d3af4828d584a6b326b669967aebbd4a287665e876659a46cec0e89179c`，与旧 APK 不一致，因此未使用。
- 2026-07-28：检查全部 Git 对象和 `v1.3.1` 标签；历史构建脚本没有签名配置，历史对象中没有 keystore 或签名属性文件。
- 2026-07-28：完成 v1.4.0 / versionCode 7、Release 签名配置入口、缺失签名保护、配置模板和 README。
- 2026-07-28：Gradle 配置验证通过；缺失签名的 Release 入口按预期失败；单元测试两次未产生输出，暂未取得结果。
- 2026-07-28：修复 Android Studio 签名向导被自定义保护逻辑提前拦截的问题；保护逻辑现可识别完整的 `android.injected.signing.*` 参数。
- 2026-07-29：发现 `packageRelease` 前缀匹配会把 `packageReleaseResources` 误判为最终产物任务，导致标准 `test` 命令在无签名配置时失败；改为最终分发产物任务精确名单并完成正反向验证。

## 10. Decision log

- Decision：`versionCode` 从 `6` 递增到 `7`。
  - Context：Android 覆盖升级要求 versionCode 严格递增。
- Decision：必须复用 `v1.3.1` 原签名密钥。
  - Context：相同 applicationId 的覆盖升级要求签名证书一致。
- Decision：真实签名信息只允许来自 ignored 属性文件或环境变量。
  - Context：符合 constitution 的秘密与干净提交要求。
- Decision：不使用本机发现的其他项目密钥，也不自动生成新密钥。
  - Context：其证书与 `v1.3.1` 不一致，会破坏升级兼容性。
- Decision：签名缺失保护只精确匹配最终 APK/AAB 分发产物任务，不按任务名中的 `Release` 或 `packageRelease` 前缀拦截。
  - Rationale：Release JVM 单测、lint 和资源处理不产生可分发的未签名包，不应要求发布密钥。

## 11. TODO: confirm

- 用户提供 `v1.3.1` 使用的原 keystore、alias 和口令，或在本机填写被忽略的 `android/release-signing.properties`。
- 取得原密钥后运行测试、`assembleRelease`、`apksigner`，并确认新版证书 SHA-256 等于 `6808eb315b7619f375ed7a58abe24bd53125426203ceaf755d71290a46cf7af8`。
