package cn.edu.bjtu.mis.data.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_AGENT_MAX_STEPS = 20

val DEFAULT_AGENT_TOOLS = listOf(
    "file.list",
    "file.read",
    "file.write",
    "file.delete",
    "archive.extract",
    "archive.create_zip",
    "document.extract_pdf",
    "document.extract_docx",
    "document.generate_pdf",
    "document.generate_docx",
    "code.run_python",
    "code.run_js",
    "search.query",
    "search.fetch_page",
    "package.results",
)

@Serializable
data class AgentAttachment(
    val displayName: String,
    val relativePath: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
)

@Serializable
data class AgentHomeworkContext(
    val homeworkId: Int? = null,
    val courseId: Int,
    val course: String,
    val courseCode: String? = null,
    val title: String,
    val requirementText: String? = null,
    val openedAt: String? = null,
    val dueAt: String? = null,
    val submittedAt: String? = null,
    val status: String,
    val userInstruction: String? = null,
    val attachments: List<AgentAttachment> = emptyList(),
)

@Serializable
data class AgentTaskRequest(
    val prompt: String,
    val attachments: List<AgentAttachment> = emptyList(),
    val allowedTools: List<String> = DEFAULT_AGENT_TOOLS,
    val outputFormat: AgentOutputFormat = AgentOutputFormat.Auto,
    val maxSteps: Int = DEFAULT_AGENT_MAX_STEPS,
)

@Serializable
enum class AgentOutputFormat {
    @SerialName("auto")
    Auto,

    @SerialName("markdown")
    Markdown,

    @SerialName("docx")
    Docx,

    @SerialName("pdf")
    Pdf,
}

@Serializable
enum class AgentTaskStatus {
    @SerialName("queued")
    Queued,

    @SerialName("running")
    Running,

    @SerialName("succeeded")
    Succeeded,

    @SerialName("failed")
    Failed,

    @SerialName("canceled")
    Canceled,
}

@Serializable
enum class AgentStepStatus {
    @SerialName("running")
    Running,

    @SerialName("succeeded")
    Succeeded,

    @SerialName("failed")
    Failed,
}

@Serializable
enum class AgentArtifactRole {
    @SerialName("input")
    Input,

    @SerialName("intermediate")
    Intermediate,

    @SerialName("output")
    Output,

    @SerialName("package")
    Package,
}

@Serializable
data class RuntimeCapability(
    val name: String,
    val status: RuntimeStatus,
    val version: String? = null,
    val limitations: List<String> = emptyList(),
)

@Serializable
enum class RuntimeStatus {
    @SerialName("available")
    Available,

    @SerialName("unavailable")
    Unavailable,

    @SerialName("initializing")
    Initializing,

    @SerialName("failed")
    Failed,
}

@Serializable
enum class RuntimeError {
    @SerialName("runtime_unavailable")
    RuntimeUnavailable,

    @SerialName("timeout")
    Timeout,

    @SerialName("sandbox_crashed")
    SandboxCrashed,

    @SerialName("unsupported_api")
    UnsupportedApi,
}

data class AgentSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val textModel: String = "gpt-4.1-mini",
    val visionModel: String? = null,
    val requestTimeoutSeconds: Int = 60,
    val temperature: Double = 0.2,
    val searchProvider: SearchProviderType = SearchProviderType.DuckDuckGoHtml,
    val maxWorkspaceBytes: Long = 256L * 1024L * 1024L,
    val maxSteps: Int = DEFAULT_AGENT_MAX_STEPS,
)

enum class SearchProviderType {
    DuckDuckGoHtml,
}
