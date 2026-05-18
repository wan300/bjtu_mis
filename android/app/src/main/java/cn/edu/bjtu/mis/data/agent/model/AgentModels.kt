package cn.edu.bjtu.mis.data.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentAttachment(
    val displayName: String,
    val relativePath: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val extractedDir: String? = null,
    val extractedFiles: List<String> = emptyList(),
    val extractionError: String? = null,
)

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
