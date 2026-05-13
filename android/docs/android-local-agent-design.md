# Android Local Agent Design

## 1. 结论

本项目可以继续实现“轻量本地 Agent 辅助作业”能力，但不能采用“在 `filesDir` 下安装并执行 OpenClaw/Node/Python 二进制”的路线。当前 Android 应用 `targetSdk = 35`，必须遵守 Android 10/API 29+ 的 W^X 限制：不能从可写 app home directory 执行文件。后续方案应调整为：

- Android 原生工具负责文件、ZIP、PDF/DOCX、搜索、结果打包和进度跟踪。
- JS 小代码运行使用 APK 依赖内的 AndroidX JavaScriptSandbox。
- Python 小代码运行使用 APK 内置 Chaquopy 解释器能力。
- LLM/VLM 使用 OpenAI-compatible 云 API，可选通过 LiteLLM Proxy 统一供应商。
- OpenClaw 只作为 Android 长任务、状态机、日志、前台服务和工具管理 UI 的参考，不直接移植它的 `filesDir/usr/bin/sh`、Node 或 gateway 执行模型。

## 2. 环境事实

### 2.1 当前项目

当前 Android 工程事实：

- `compileSdk = 35`
- `minSdk = 26`
- `targetSdk = 35`
- Android Gradle Plugin 为 `8.7.3`
- Kotlin 为 `2.0.21`
- 已有 Room、DataStore、WorkManager、OkHttp、Jsoup、FileProvider、ForegroundService、Android Keystore 风格本地加密存储。
- Room 当前 `version = 3`，已有 `MIGRATION_1_2` 与 `MIGRATION_2_3`。新增 Agent 表时必须升到 `version = 4` 并添加 `MIGRATION_3_4`。

### 2.2 OpenClaw Android

外部 OpenClaw Android 项目的关键事实（基于本项目调研）：

- OpenClaw Android 当前 `targetSdk = 28`。
- 它将 `usr`、`home`、`tmp` 放在 `context.filesDir` 下。
- 它用 `ProcessBuilder("$PREFIX/bin/sh", "-c", command)` 运行命令。
- 它检查并执行 `filesDir/usr/bin/sh`、`filesDir/usr/bin/node` 等二进制。

这条路线对 `targetSdk = 28` 的独立应用可工作，但不能直接用于本项目的 `targetSdk = 35` 主应用。

### 2.3 Android W^X 限制

Android 10/API 29+ 对面向 API 29 及以上的应用移除了 app home directory 的执行权限。官方文档说明：从可写 app home directory 执行文件是 W^X 违规，应用应只加载嵌入 APK 的二进制代码。

本项目必须采用以下硬约束：

- 禁止从 `filesDir`、`dataDir`、`cacheDir`、workspace、下载目录或 SAF 授权目录执行二进制。
- 禁止下载、解压、`chmod +x` 后执行 Node、CPython、OpenClaw、shell 或任意 ELF。
- 禁止在 Agent 工具中暴露任意 shell。
- 如需 native binary 或 interpreter，只能通过 APK 打包的 native library / SDK 形式提供。

## 3. v1 能力边界

### 3.1 保留能力

v1 继续支持以下能力目标：

- 文件读写：只在 Agent task workspace 内操作。
- 联网搜索：默认 DuckDuckGo HTML，后续可替换 Tavily、Serper、Brave Search API。
- ZIP 解压与打包：只支持 ZIP，RAR/7z/tar.gz 后续扩展。
- PDF/DOCX 提取与生成：基础文本提取和简单文档生成。
- JS 小代码运行：AndroidX JavaScriptSandbox，受限 ECMAScript。
- Python 小代码运行：Chaquopy 内置 Python，受限标准库和构建期依赖。
- 进度跟踪：Room 持久化任务、步骤、产物。
- 结果打包：生成 `results.zip`，通过 SAF/share sheet 导出。
- LLM/VLM：OpenAI-compatible HTTP API，API key 本地加密保存。

### 3.2 非目标

v1 不做以下事情：

- 不运行完整 OpenClaw gateway。
- 不内置 Node.js runtime。
- 不支持 npm、`require()`、shell、bash、apt、pip 运行时安装。
- 不支持从 `filesDir` 执行任何下载或写入的二进制。
- 不做本地 LLM/VLM 推理。
- 不承诺 Python/JS runner 是强安全沙箱。
- 不保证复杂 PDF/DOCX 排版、公式、批注、脚注和宏的完整保真。

## 4. 技术选型

### 4.1 JS Runner

选择 AndroidX JavaScriptEngine：

```toml
[versions]
javascriptengine = "1.1.0"

[libraries]
androidx-javascriptengine = { module = "androidx.javascriptengine:javascriptengine", version.ref = "javascriptengine" }
```

模块依赖：

```kotlin
implementation(libs.androidx.javascriptengine)
```

能力定位：

- 只执行 ECMAScript 小脚本。
- 输入输出通过 JSON 字符串传递。
- 不提供 Node.js API、npm、`require()`、文件系统、网络或 shell。
- stdout/stderr 由 wrapper 模拟，例如提供 `console.log` 收集输出。
- 每次工具调用创建独立 isolate，任务结束后关闭。
- 支持超时取消；sandbox crash 记录为 `sandbox_crashed`。
- 若设备不支持 JavaScriptSandbox，`code.run_js` 返回 `runtime_unavailable`。

### 4.2 Python Runner

选择 Chaquopy：

```toml
[versions]
chaquopy = "17.0.0"

[plugins]
chaquopy = { id = "com.chaquo.python", version.ref = "chaquopy" }
```

顶层 Gradle：

```kotlin
plugins {
    alias(libs.plugins.chaquopy) apply false
}
```

App 模块：

```kotlin
plugins {
    alias(libs.plugins.chaquopy)
}
```

默认 ABI：

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}
```

Python 版本建议：

```kotlin
chaquopy {
    defaultConfig {
        version = "3.13"
    }
}
```

能力定位：

- Python 解释器随 APK 打包，不从 app home directory 执行 CPython 二进制。
- 用户代码作为文本数据交给 Chaquopy 运行。
- v1 只承诺标准库和构建期固定依赖。
- 不支持运行时 `pip install`。
- 不向 Python 环境注入 LLM API key、登录凭据、Cookie 或系统环境变量。
- Python 运行在独立 Android 进程服务中，建议声明为 `:agent_python`。超时、死循环或 native crash 时，主进程可停止任务并重建服务连接。

### 4.3 文档工具

PDF：

- 文本提取：使用 PDFBox-Android 或等价 Android 可用库。
- 生成：使用 Android `PdfDocument`。
- 图像型 PDF：v1 只返回 warning；若配置 VLM，可将页面渲染图交给云端视觉模型辅助识别。

DOCX：

- 提取：DOCX 本质为 ZIP，使用 Java ZIP API + XMLPullParser 读取 `word/document.xml`。
- 生成：写入最小 OPC/DOCX 模板，支持标题、段落、列表、简单表格。
- 不执行宏，不加载外链，不下载远程资源。

### 4.4 搜索工具

默认实现：

- OkHttp 请求 DuckDuckGo HTML。
- Jsoup 解析标题、链接、摘要。
- 每次搜索默认返回 5 条。
- `search.fetch_page` 只处理 HTML，正文提取最大 `128 KiB`。

替换接口：

```kotlin
interface SearchProvider {
    suspend fun query(query: String, limit: Int = 5): List<SearchResult>
}
```

后续可增加：

- Tavily
- Serper
- Brave Search API
- 学校站内搜索

### 4.5 OpenClaw 的可复用部分

可以借鉴：

- bootstrap 状态机
- runtime capability 检查 UI
- 前台服务保活和通知模式
- 日志结构
- 工具安装状态页面
- 长任务错误呈现

不能复用：

- `filesDir/usr` runtime 布局
- `ProcessBuilder("$PREFIX/bin/sh", "-c", command)`
- `filesDir` 下 Node/OpenClaw/AI CLI 二进制执行
- 开放式 shell 和 gateway

如后续确需完整 OpenClaw 能力，只能作为外部 companion：

- Termux/OpenClaw 独立运行。
- 单独 companion app 运行。
- 本 app 通过显式 HTTP、Binder 或 content provider IPC 连接。
- 用户必须明确安装、授权和连接该 companion。

## 5. 总体架构

```text
Compose UI
  -> AgentViewModel
  -> AgentRepository
  -> Room: tasks / steps / artifacts
  -> DataStore + encrypted key store: Agent settings
  -> AgentForegroundService / AgentWorker
  -> AgentOrchestrator
     -> LlmClient(OpenAI-compatible)
     -> ToolRegistry
        -> FileTool
        -> ArchiveTool
        -> DocumentTool
        -> CodeTool
        -> SearchTool
        -> PackageTool
     -> WorkspaceManager
     -> RuntimeManager
        -> JsSandboxRuntime(AndroidX JavaScriptSandbox)
        -> PythonRuntime(Chaquopy service process)
```

建议新增包：

```text
cn.edu.bjtu.mis.data.agent.model
cn.edu.bjtu.mis.data.agent.db
cn.edu.bjtu.mis.data.agent.repository
cn.edu.bjtu.mis.data.agent.runtime
cn.edu.bjtu.mis.data.agent.llm
cn.edu.bjtu.mis.data.agent.tools
cn.edu.bjtu.mis.data.agent.search
cn.edu.bjtu.mis.data.agent.document
cn.edu.bjtu.mis.data.agent.service
cn.edu.bjtu.mis.ui.screens.AgentScreen
```

`AppContainer` 新增：

- `AgentSettingsStore`
- `AgentSecretStore`
- `AgentRepository`
- `LlmClient`
- `RuntimeManager`
- `ToolRegistry`
- `AgentOrchestrator`

## 6. 数据模型

### 6.1 Kotlin Models

```kotlin
@Serializable
data class AgentTaskRequest(
    val prompt: String,
    val attachments: List<AgentAttachment> = emptyList(),
    val allowedTools: List<String> = DEFAULT_AGENT_TOOLS,
    val outputFormat: AgentOutputFormat = AgentOutputFormat.AUTO,
    val maxSteps: Int = 20,
)

enum class AgentTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED,
}

@Serializable
data class AgentStep(
    val taskId: String,
    val toolName: String,
    val inputJson: String,
    val status: AgentStepStatus,
    val startedAt: String,
    val finishedAt: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
)

@Serializable
data class AgentArtifact(
    val taskId: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val role: AgentArtifactRole,
)

@Serializable
data class RuntimeCapability(
    val name: String,
    val status: RuntimeStatus,
    val version: String? = null,
    val limitations: List<String> = emptyList(),
)

enum class RuntimeStatus {
    AVAILABLE,
    UNAVAILABLE,
    INITIALIZING,
    FAILED,
}

enum class RuntimeError {
    RUNTIME_UNAVAILABLE,
    TIMEOUT,
    SANDBOX_CRASHED,
    UNSUPPORTED_API,
}
```

### 6.2 Room Entities

新增表：

```text
agent_tasks
- id TEXT PRIMARY KEY
- prompt TEXT NOT NULL
- status TEXT NOT NULL
- allowed_tools_json TEXT NOT NULL
- output_format TEXT NOT NULL
- max_steps INTEGER NOT NULL
- final_answer TEXT
- error_message TEXT
- created_at TEXT NOT NULL
- updated_at TEXT NOT NULL
- started_at TEXT
- finished_at TEXT

agent_steps
- id TEXT PRIMARY KEY
- task_id TEXT NOT NULL
- step_index INTEGER NOT NULL
- tool_name TEXT NOT NULL
- input_json TEXT NOT NULL
- status TEXT NOT NULL
- stdout TEXT
- stderr TEXT
- error_message TEXT
- started_at TEXT NOT NULL
- finished_at TEXT

agent_artifacts
- id TEXT PRIMARY KEY
- task_id TEXT NOT NULL
- relative_path TEXT NOT NULL
- mime_type TEXT NOT NULL
- size_bytes INTEGER NOT NULL
- role TEXT NOT NULL
- created_at TEXT NOT NULL
```

索引：

- `agent_steps(task_id, step_index)`
- `agent_artifacts(task_id, role)`
- `agent_tasks(status, updated_at)`

迁移：

- `AppDatabase.version` 从 `3` 升到 `4`。
- `entities` 增加 `AgentTaskEntity`、`AgentStepEntity`、`AgentArtifactEntity`。
- 新增 `MIGRATION_3_4` 创建三张表和索引。
- `Room.databaseBuilder(...).addMigrations(...)` 增加 `MIGRATION_3_4`。

### 6.3 Settings

DataStore 保存非敏感配置：

```kotlin
data class AgentSettings(
    val baseUrl: String,
    val textModel: String,
    val visionModel: String?,
    val requestTimeoutSeconds: Int = 60,
    val temperature: Double = 0.2,
    val searchProvider: SearchProviderType = SearchProviderType.DUCKDUCKGO_HTML,
    val maxWorkspaceBytes: Long = 256L * 1024L * 1024L,
    val maxSteps: Int = 20,
)
```

API key 使用 Android Keystore 加密后保存，复用现有 `SecureCredentialStore`/`SecureCookieStore` 的实现风格，新增独立 `AgentSecretStore`。

## 7. 工作区

目录结构：

```text
files/agent-workspaces/{taskId}/
  inbox/
  work/
  output/
  logs/
  results.zip
```

规则：

- 用户附件通过 SAF 复制到 `inbox/`。
- `inbox/` 默认只读，工具不得覆盖或删除。
- `work/` 保存中间文件。
- `output/` 保存最终产物。
- `logs/` 保存工具审计日志。
- 所有工具路径均为相对路径。
- 禁止绝对路径、`..`、Windows drive path、符号链接越界。
- 解析后的 canonical path 必须仍在当前任务 workspace 内。
- 默认单文件最大 `50 MiB`。
- 默认任务 workspace 最大 `256 MiB`。

## 8. 工具规范

v1 固定工具：

- `file.list`
- `file.read`
- `file.write`
- `file.delete`
- `archive.extract`
- `archive.create_zip`
- `document.extract_pdf`
- `document.extract_docx`
- `document.generate_pdf`
- `document.generate_docx`
- `code.run_python`
- `code.run_js`
- `search.query`
- `search.fetch_page`
- `package.results`

通用规则：

- 输入必须是 JSON object。
- 输出必须是 JSON object。
- 工具调用写入 `agent_steps`。
- 工具产物写入 `agent_artifacts`。
- 任何工具不得调用 shell。
- 任何工具不得执行 app home、cache、workspace、下载目录或 SAF 目录中的二进制。
- 工具失败不直接终止 app；由 `AgentOrchestrator` 决定是否让模型修复或结束任务。

### 8.1 FileTool

`file.list`：

```json
{ "path": "work", "recursive": false }
```

`file.read`：

```json
{ "path": "work/input.txt" }
```

`file.write`：

```json
{ "path": "output/answer.md", "content": "...", "append": false }
```

限制：

- `file.read` 只读 UTF-8 文本，二进制文件返回 `unsupported_file_type`。
- `file.read` 输出最多 `128 KiB`，超出时返回截断标记。
- `file.write` 只能写 `work/` 或 `output/`。
- `file.delete` 只能删除 `work/` 或 `output/` 中的文件，不删除目录树。

### 8.2 ArchiveTool

`archive.extract`：

```json
{ "archivePath": "inbox/homework.zip", "targetDir": "work/unpacked" }
```

`archive.create_zip`：

```json
{ "sourceDir": "output", "zipPath": "output/answer.zip" }
```

限制：

- v1 只支持 ZIP。
- 拒绝 zip-slip。
- 拒绝绝对路径、空路径、Windows drive path、符号链接 entry。
- 默认最多 `500` 个 entry。
- 解压后总大小最多 `256 MiB`。
- 单文件最多 `50 MiB`。
- ZIP 中只写相对路径，不写设备绝对路径。

### 8.3 DocumentTool

`document.extract_pdf`：

```json
{ "path": "inbox/reference.pdf", "outputPath": "work/reference.md" }
```

`document.extract_docx`：

```json
{ "path": "inbox/homework.docx", "outputPath": "work/homework.md" }
```

`document.generate_pdf`：

```json
{ "title": "作业答案", "contentMarkdown": "# 标题\n\n正文", "outputPath": "output/answer.pdf" }
```

`document.generate_docx`：

```json
{ "title": "作业答案", "contentMarkdown": "# 标题\n\n正文", "outputPath": "output/answer.docx" }
```

限制：

- PDF/DOCX 提取输出 Markdown-like 文本。
- 复杂格式降级为 warning。
- 图像型 PDF 不做本地 OCR；如配置 VLM，可让模型辅助识别。
- 不执行宏，不解析外链，不加载远程资源。

### 8.4 CodeTool: JS

`code.run_js`：

```json
{
  "code": "return input.a + input.b;",
  "input": { "a": 1, "b": 2 },
  "timeoutSeconds": 5
}
```

实现：

- 使用 AndroidX JavaScriptSandbox。
- wrapper 将 `input` 注入 isolate。
- wrapper 收集 `console.log` 作为 stdout。
- 结果必须 JSON 可序列化。

限制：

- 不支持 Node.js API。
- 不支持 npm。
- 不支持 `require()`。
- 不支持直接文件系统和网络访问。
- 默认超时 `5s`，最大 `30s`。
- stdout/stderr 各最多 `64 KiB`。

### 8.5 CodeTool: Python

`code.run_python`：

```json
{
  "code": "result = input['a'] + input['b']",
  "input": { "a": 1, "b": 2 },
  "timeoutSeconds": 10
}
```

实现：

- 使用 Chaquopy。
- Python 服务运行在 `:agent_python` 独立进程。
- 主进程通过 bound service 或 messenger 传入代码、输入 JSON、workspace 根路径和限制参数。
- Python 侧只提供受控 helper：读取/写入 workspace 文件、JSON 编码、stdout/stderr 捕获。
- 超时后主进程标记步骤失败，并重建 Python service 连接。

限制：

- 不支持运行时 `pip install`。
- 不支持 shell。
- 不传入 API key、Cookie、登录凭据。
- 默认超时 `10s`，最大 `60s`。
- stdout/stderr 各最多 `64 KiB`。
- Python 写文件仍必须走 workspace 路径校验。

### 8.6 SearchTool

`search.query`：

```json
{ "query": "题目关键词", "limit": 5 }
```

`search.fetch_page`：

```json
{ "url": "https://example.com/page.html" }
```

限制：

- 默认超时 `15s`。
- 响应体最多 `2 MiB`。
- 只处理 HTML。
- 正文输出最多 `128 KiB`。
- 不自动下载附件。

### 8.7 PackageTool

`package.results` 生成：

```text
results.zip
  final_answer.md
  artifacts/
  logs/steps.json
  manifest.json
```

`manifest.json`：

```json
{
  "taskId": "uuid",
  "createdAt": "2026-05-10T00:00:00Z",
  "finishedAt": "2026-05-10T00:05:00Z",
  "status": "succeeded",
  "artifacts": [
    { "path": "artifacts/answer.docx", "mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document" }
  ]
}
```

导出必须通过 SAF `ACTION_CREATE_DOCUMENT` 或 Android share sheet，不直接写公共存储路径。

## 9. Agent 执行流

1. 用户在 `AgentScreen` 输入任务目标并选择附件。
2. App 通过 SAF 读取附件，复制到 `inbox/`。
3. `AgentRepository` 创建 `AgentTaskEntity(status = QUEUED)`。
4. `AgentForegroundService` 启动通知，`AgentWorker` 或 service coroutine 执行任务。
5. `AgentOrchestrator` 构造模型上下文：用户 prompt、附件清单、课程/作业快照摘要、工具 schema、runtime capability。
6. `LlmClient` 调用 OpenAI-compatible chat completions。
7. 模型返回 tool call 时，`ToolRegistry` 查找工具并执行。
8. 每次工具调用写入 step，输出和产物持久化。
9. 模型给出最终答案、达到 `maxSteps`、用户取消或不可恢复错误时结束任务。
10. `PackageTool` 生成 `results.zip`。
11. UI 展示最终答案、步骤时间线、产物和导出入口。

## 10. LLM/VLM Client

配置：

- `baseUrl`
- `apiKey`
- `textModel`
- `visionModel`
- `requestTimeoutSeconds`
- `temperature`

请求协议：

- OpenAI-compatible `/v1/chat/completions`。
- 优先使用 tool calling。
- 如果供应商不支持 tool calling，则要求模型输出严格 JSON：

```json
{ "type": "tool_call", "name": "file.read", "arguments": { "path": "work/a.txt" } }
```

最终答案：

```json
{ "type": "final", "answer": "..." }
```

VLM：

- 仅在 `visionModel` 已配置时可用。
- 图片输入由用户附件或 PDF 页面渲染产生。
- 默认每次最多 4 张图。
- 每张图压缩到长边不超过 `1600px`。

## 11. UI

新增“作业助手”页面：

- 任务输入框。
- 附件导入。
- 允许工具选择。
- 模型配置状态提示。
- 运行按钮和取消按钮。
- 步骤时间线。
- 最终答案。
- 产物列表。
- 结果 ZIP 导出。

新增设置项：

- Base URL
- API Key
- Text model
- Vision model
- Search provider
- Max steps
- Runtime capabilities
- Clear Agent cache

UI 必须明确提示：

- 代码运行仅用于小脚本辅助。
- JS 不是 Node.js。
- Python 不支持运行时安装包。
- 结果需要用户自行核对后提交。

## 12. 安全与隐私

- 附件复制到 app 私有 workspace 后处理。
- 默认不把原始文件上传给 LLM；只上传 prompt、提取文本、文件名和必要摘要。
- VLM 仅在用户配置 vision model 且任务需要时上传图片。
- API key 不写入 Room、日志、stdout/stderr、结果 ZIP。
- 登录凭据、Cookie 不传给 Agent runner。
- Python/JS 运行环境不注入敏感环境变量。
- 清理任务时删除 workspace、steps、artifacts。
- 所有工具路径必须经过 `WorkspaceManager` 校验。

## 13. 错误处理

| 场景 | 行为 |
| --- | --- |
| 未配置模型 | 不启动任务，跳转设置 |
| API key 错误 | 任务失败，提示检查模型配置 |
| 网络不可用 | 保留已完成步骤，任务失败或等待重试 |
| JS sandbox 不可用 | `code.run_js` 返回 `runtime_unavailable` |
| JS sandbox crash | 步骤失败，记录 `sandbox_crashed` |
| Python 未打包或初始化失败 | `code.run_python` 返回 `runtime_unavailable` |
| Python 超时 | 标记 timeout，重建 Python service |
| 路径越界 | 工具失败，记录安全错误 |
| ZIP 越界或超限 | 拒绝解压 |
| 用户取消 | 标记 `CANCELED`，停止后续工具 |

## 14. 开发里程碑

### M0: 文档与依赖规划

- 使用本文作为实现基线。
- 添加 Gradle version catalog 项：JavaScriptEngine、Chaquopy、PDFBox-Android。
- 明确 APK 包体影响和 ABI 策略。

### M1: 数据与工作区

- Room 升级到 version 4。
- 添加 Agent 三张表和 `MIGRATION_3_4`。
- 实现 `WorkspaceManager`、附件导入、结果导出。
- 实现 `file.*` 与 `package.results`。

### M2: LLM 与任务编排

- 实现 `AgentSettingsStore`、`AgentSecretStore`。
- 实现 OpenAI-compatible `LlmClient`。
- 实现 `AgentOrchestrator` tool loop。

### M3: 搜索与 ZIP

- 实现 `search.query`、`search.fetch_page`。
- 实现 `archive.extract`、`archive.create_zip`。
- 完成 zip-slip 和超限测试。

### M4: 文档工具

- 实现 PDF/DOCX 基础提取。
- 实现 PDF/DOCX 基础生成。
- 对复杂格式返回 warning。

### M5: JS/Python Runner

- 接入 AndroidX JavaScriptSandbox。
- 接入 Chaquopy。
- 新增 `:agent_python` 进程服务。
- 实现 `code.run_js` 与 `code.run_python`。
- 验证没有 app-home 二进制执行链。

### M6: UI 与验收

- 新增 Agent 页面和设置页。
- 接入前台服务通知。
- 完成取消、失败、导出、清理流程。
- 完成单元测试和 instrumentation 测试。

## 15. 测试计划

### 15.1 静态约束

必须通过静态搜索确认 Agent 实现中不存在：

- `ProcessBuilder(filesDir...)`
- `Runtime.exec(filesDir...)`
- `chmod +x` app home 文件
- `bin/sh -c` 执行链
- 执行 workspace、cache、download、SAF 文件

### 15.2 单元测试

- Room migration：version 3 到 4 保留旧数据并创建 Agent 表。
- Workspace：绝对路径、`..`、符号链接、Windows drive path 均被拒绝。
- FileTool：读写、截断、禁写 `inbox/`。
- ArchiveTool：zip-slip、entry 数、总大小、单文件大小。
- SearchTool：正常结果、空结果、超时、HTML 结构变化。
- DocumentTool：PDF/DOCX 基础提取、生成、损坏文档、复杂格式 warning。
- JS runner：JSON 输入输出、超时、sandbox crash、Node API 不可用。
- Python runner：正常执行、异常、超时、stdout/stderr 截断、敏感配置隔离。
- LLM client：tool calling、JSON fallback、最终答案、模型错误。

### 15.3 Android Instrumentation

- 通过文件选择器导入附件。
- 创建任务并观察步骤时间线。
- 前台服务通知显示当前步骤。
- 取消任务后状态为 `CANCELED`。
- 导出 `results.zip`。
- 断网、401、模型超时、JS/Python runtime 不可用时 UI 有可读错误。

### 15.4 回归检查

```powershell
Set-Location d:\code\project\bjtu_web\android
.\gradlew.bat test

Set-Location d:\code\project\bjtu_web
Set-Location d:\code\project\bjtu_web\android
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

## 16. 验收标准

- Android 端可创建 Agent 任务并持久化任务、步骤、产物。
- Agent 只能读写当前任务 workspace。
- Agent 不能执行 app home、cache、workspace、下载目录或 SAF 目录中的二进制。
- JS runner 使用 AndroidX JavaScriptSandbox，不支持 Node/npm 时错误清晰。
- Python runner 使用 Chaquopy，不支持运行时 pip 安装。
- 文件、搜索、ZIP、文档、JS、Python、结果打包各有成功路径测试。
- API key 不进入日志、Room、stdout/stderr 或导出 ZIP。
- `results.zip` 可通过 SAF/share sheet 导出。

## 17. 参考来源

- Android behavior changes: removed execute permission for app home directory: https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
- AndroidX JavaScriptEngine release notes: https://developer.android.com/jetpack/androidx/releases/javascriptengine
- Chaquopy Android Gradle plugin docs: https://chaquo.com/chaquopy/doc/current/android.html
- OpenClaw Android: https://github.com/AidanPark/openclaw-android
- LiteLLM: https://github.com/BerriAI/litellm
