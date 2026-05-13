package cn.edu.bjtu.mis.data.agent.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_tasks",
    indices = [
        Index(value = ["status", "updated_at"]),
        Index(value = ["source_kind", "source_ref"]),
    ],
)
data class AgentTaskEntity(
    @PrimaryKey
    val id: String,
    val prompt: String,
    val status: String,
    @ColumnInfo(name = "allowed_tools_json")
    val allowedToolsJson: String,
    @ColumnInfo(name = "output_format")
    val outputFormat: String,
    @ColumnInfo(name = "max_steps")
    val maxSteps: Int,
    @ColumnInfo(name = "final_answer")
    val finalAnswer: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
    @ColumnInfo(name = "started_at")
    val startedAt: String? = null,
    @ColumnInfo(name = "finished_at")
    val finishedAt: String? = null,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String? = null,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,
    val title: String? = null,
    @ColumnInfo(name = "context_json")
    val contextJson: String? = null,
)

@Entity(
    tableName = "agent_steps",
    indices = [
        Index(value = ["task_id", "step_index"], unique = true),
    ],
)
data class AgentStepEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "step_index")
    val stepIndex: Int,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
    @ColumnInfo(name = "input_json")
    val inputJson: String,
    val status: String,
    val stdout: String? = null,
    val stderr: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "started_at")
    val startedAt: String,
    @ColumnInfo(name = "finished_at")
    val finishedAt: String? = null,
)

@Entity(
    tableName = "agent_artifacts",
    indices = [
        Index(value = ["task_id", "role"]),
    ],
)
data class AgentArtifactEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    val role: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)

@Entity(
    tableName = "agent_messages",
    indices = [
        Index(value = ["task_id", "message_index"], unique = true),
    ],
)
data class AgentMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "message_index")
    val messageIndex: Int,
    val role: String,
    val content: String,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
