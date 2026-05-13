package cn.edu.bjtu.mis.data.agent.repository

import android.net.Uri
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.agent.db.AgentArtifactEntity
import cn.edu.bjtu.mis.data.agent.db.AgentDao
import cn.edu.bjtu.mis.data.agent.db.AgentMessageEntity
import cn.edu.bjtu.mis.data.agent.db.AgentStepEntity
import cn.edu.bjtu.mis.data.agent.db.AgentTaskEntity
import cn.edu.bjtu.mis.data.agent.llm.AgentChatMessage
import cn.edu.bjtu.mis.data.agent.llm.LlmClient
import cn.edu.bjtu.mis.data.agent.llm.LlmStreamEvent
import cn.edu.bjtu.mis.data.agent.llm.LlmToolCall
import cn.edu.bjtu.mis.data.agent.model.AgentArtifactRole
import cn.edu.bjtu.mis.data.agent.model.AgentHomeworkContext
import cn.edu.bjtu.mis.data.agent.model.AgentOutputFormat
import cn.edu.bjtu.mis.data.agent.model.AgentTaskStatus
import cn.edu.bjtu.mis.data.agent.model.DEFAULT_AGENT_TOOLS
import cn.edu.bjtu.mis.data.agent.tools.ToolArtifact
import cn.edu.bjtu.mis.data.agent.tools.ToolRegistry
import cn.edu.bjtu.mis.data.agent.tools.WorkspaceManager
import cn.edu.bjtu.mis.data.agent.tools.openAiSchema
import cn.edu.bjtu.mis.data.agent.tools.parseToolArguments
import cn.edu.bjtu.mis.data.repository.nowIso
import cn.edu.bjtu.mis.model.HomeworkItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class AgentRepository(
    private val dao: AgentDao,
    private val settingsStore: AgentSettingsStore,
    private val secretStore: AgentSecretStore,
    private val workspaceManager: WorkspaceManager,
    private val toolRegistry: ToolRegistry,
    private val llmClient: LlmClient,
) {
    fun observeTasks(): Flow<List<AgentTaskEntity>> = dao.observeTasks()
    fun observeTask(taskId: String): Flow<AgentTaskEntity?> = dao.observeTask(taskId)
    fun observeMessages(taskId: String): Flow<List<AgentMessageEntity>> = dao.observeMessages(taskId)
    fun observeSteps(taskId: String): Flow<List<AgentStepEntity>> = dao.observeSteps(taskId)
    fun observeArtifacts(taskId: String): Flow<List<AgentArtifactEntity>> = dao.observeArtifacts(taskId)

    suspend fun markStaleActiveTasks() {
        dao.failStaleActiveTasks("应用重启后保留日志，v1 不自动恢复运行中的 Agent 任务。", nowIso())
    }

    suspend fun createHomeworkTask(
        homework: HomeworkItem,
        userInstruction: String,
        attachmentUris: List<Uri>,
    ): String {
        val taskId = UUID.randomUUID().toString()
        val now = nowIso()
        val settings = settingsStore.settings.first()
        val attachments = workspaceManager.importUris(taskId, attachmentUris)
        val context = AgentHomeworkContext(
            homeworkId = homework.homeworkId,
            courseId = homework.courseId,
            course = homework.course,
            courseCode = homework.courseCode,
            title = homework.title,
            requirementText = homework.requirementText ?: homework.contentExcerpt,
            openedAt = homework.openedAt,
            dueAt = homework.dueAt,
            submittedAt = homework.submittedAt,
            status = homework.status,
            userInstruction = userInstruction.takeIf { it.isNotBlank() },
            attachments = attachments,
        )
        val prompt = buildHomeworkPrompt(context)
        dao.insertTask(
            AgentTaskEntity(
                id = taskId,
                prompt = prompt,
                status = "queued",
                allowedToolsJson = AppJson.encodeToString(DEFAULT_AGENT_TOOLS),
                outputFormat = AgentOutputFormat.Auto.name,
                maxSteps = settings.maxSteps,
                createdAt = now,
                updatedAt = now,
                sourceKind = "homework",
                sourceRef = homework.homeworkId?.toString() ?: "${homework.courseId}:${homework.title}",
                title = homework.title,
                contextJson = AppJson.encodeToString(context),
            )
        )
        insertMessage(taskId, "user", prompt)
        attachments.forEach { attachment ->
            dao.insertArtifact(
                AgentArtifactEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    relativePath = attachment.relativePath,
                    mimeType = attachment.mimeType ?: "application/octet-stream",
                    sizeBytes = attachment.sizeBytes ?: 0L,
                    role = "input",
                    createdAt = now,
                )
            )
        }
        return taskId
    }

    suspend fun appendUserMessage(taskId: String, content: String) {
        if (content.isBlank()) return
        insertMessage(taskId, "user", content.trim())
    }

    suspend fun cancel(taskId: String) {
        dao.cancelTask(taskId, "用户已取消 Agent 任务。", nowIso())
    }

    suspend fun clearAll() {
        dao.clearArtifacts()
        dao.clearMessages()
        dao.clearSteps()
        dao.clearTasks()
    }

    suspend fun runTask(taskId: String) {
        val task = dao.getTask(taskId) ?: return
        val settings = settingsStore.settings.first()
        val apiKey = secretStore.loadApiKey()
        if (apiKey.isNullOrBlank()) {
            dao.finishTask(taskId, "failed", null, "未配置 Agent API Key。", nowIso(), nowIso())
            return
        }
        dao.markTaskStarted(taskId, "running", nowIso(), nowIso())
        runCatching {
            var turns = 0
            while (turns < task.maxSteps) {
                ensureNotCanceled(taskId)
                turns += 1
                val assistantMessageId = insertMessage(taskId, "assistant", "")
                val buffer = StringBuilder()
                var lastFlush = 0L
                val llmResult = llmClient.streamChat(
                    settings = settings,
                    apiKey = apiKey,
                    messages = buildMessages(taskId),
                    tools = toolRegistry.allTools.map { it.openAiSchema() },
                ) { event ->
                    if (event is LlmStreamEvent.ContentDelta) {
                        buffer.append(event.delta)
                        val nowMillis = System.currentTimeMillis()
                        if (nowMillis - lastFlush >= 500) {
                            dao.updateMessage(assistantMessageId, buffer.toString(), null, nowIso())
                            lastFlush = nowMillis
                        }
                    }
                }
                dao.updateMessage(
                    assistantMessageId,
                    buffer.toString().ifBlank { llmResult.content },
                    encodeToolCalls(llmResult.toolCalls).takeIf { llmResult.toolCalls.isNotEmpty() },
                    nowIso(),
                )
                if (llmResult.toolCalls.isEmpty()) {
                    val finalAnswer = llmResult.content.ifBlank { buffer.toString() }.trim()
                    executeToolCall(taskId, "package.results", buildJsonObject { put("finalAnswer", finalAnswer) })
                    dao.finishTask(taskId, "succeeded", finalAnswer, null, nowIso(), nowIso())
                    return
                }
                llmResult.toolCalls.forEach { call ->
                    ensureNotCanceled(taskId)
                    val output = runCatching { parseToolArguments(call.arguments) }
                        .mapCatching { args -> executeToolCall(taskId, call.name, args) }
                        .getOrElse { error -> buildJsonObject { put("ok", false); put("error", error.message ?: "tool call failed") } }
                    insertMessage(
                        taskId = taskId,
                        role = "tool",
                        content = output.toString(),
                        toolCallId = call.id,
                        toolName = call.name,
                    )
                }
            }
            dao.finishTask(taskId, "failed", null, "达到最大步骤数 ${task.maxSteps}，任务已停止。", nowIso(), nowIso())
        }.onFailure { error ->
            val latest = dao.getTask(taskId)
            if (latest?.status == "canceled") return
            dao.finishTask(taskId, "failed", null, error.message ?: "Agent 任务失败", nowIso(), nowIso())
        }
    }

    private suspend fun executeToolCall(taskId: String, toolName: String, arguments: JsonObject): JsonObject {
        val tool = toolRegistry.get(toolName)
            ?: return buildJsonObject { put("ok", false); put("error", "unknown_tool"); put("message", "未知工具 $toolName") }
        val stepId = UUID.randomUUID().toString()
        val startedAt = nowIso()
        dao.insertStep(
            AgentStepEntity(
                id = stepId,
                taskId = taskId,
                stepIndex = dao.nextStepIndex(taskId),
                toolName = toolName,
                inputJson = arguments.toString(),
                status = "running",
                startedAt = startedAt,
            )
        )
        return runCatching {
            val result = tool.execute(taskId, arguments)
            result.artifacts.forEach { artifact -> insertArtifact(taskId, artifact) }
            dao.finishStep(stepId, "succeeded", result.stdout, result.stderr, null, nowIso())
            result.output
        }.getOrElse { error ->
            val message = error.message ?: "工具执行失败"
            dao.finishStep(stepId, "failed", null, message, message, nowIso())
            buildJsonObject { put("ok", false); put("error", message) }
        }
    }

    private suspend fun insertArtifact(taskId: String, artifact: ToolArtifact) {
        dao.insertArtifact(
            AgentArtifactEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                relativePath = artifact.relativePath,
                mimeType = artifact.mimeType,
                sizeBytes = artifact.sizeBytes,
                role = artifact.role,
                createdAt = nowIso(),
            )
        )
    }

    private suspend fun insertMessage(
        taskId: String,
        role: String,
        content: String,
        toolCallId: String? = null,
        toolName: String? = null,
        metadataJson: String? = null,
    ): String {
        val now = nowIso()
        val id = UUID.randomUUID().toString()
        dao.insertMessage(
            AgentMessageEntity(
                id = id,
                taskId = taskId,
                messageIndex = dao.nextMessageIndex(taskId),
                role = role,
                content = content,
                toolCallId = toolCallId,
                toolName = toolName,
                metadataJson = metadataJson,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id
    }

    private suspend fun buildMessages(taskId: String): List<AgentChatMessage> {
        val dbMessages = dao.getMessages(taskId)
        val toolMessages = dbMessages.filter { it.role == "tool" }
        val usedToolMessageIds = mutableSetOf<String>()
        val ordered = mutableListOf<AgentChatMessage>()
        dbMessages.filter { it.role != "tool" }.forEach { message ->
            val toolCalls = decodeToolCalls(message.metadataJson)
            ordered += AgentChatMessage(
                role = message.role,
                content = message.content,
                toolCalls = toolCalls,
            )
            if (message.role == "assistant" && toolCalls.isNotEmpty()) {
                toolCalls.forEach { call ->
                    toolMessages.firstOrNull { it.toolCallId == call.id }?.let { toolMessage ->
                        usedToolMessageIds += toolMessage.id
                        ordered += AgentChatMessage(
                            role = "tool",
                            content = toolMessage.content,
                            toolCallId = toolMessage.toolCallId,
                            toolName = toolMessage.toolName,
                        )
                    }
                }
            }
        }
        toolMessages.filterNot { it.id in usedToolMessageIds }.forEach { message ->
            ordered += AgentChatMessage(
                role = "tool",
                content = message.content,
                toolCallId = message.toolCallId,
                toolName = message.toolName,
            )
        }
        return listOf(AgentChatMessage("system", systemPrompt())) + ordered
    }

    private suspend fun ensureNotCanceled(taskId: String) {
        if (dao.getTask(taskId)?.status == "canceled") throw CancellationException()
    }

    private fun buildHomeworkPrompt(context: AgentHomeworkContext): String =
        buildString {
            appendLine("请协助完成以下北交大课程平台作业。你只能生成答案和产物，不能自动提交。")
            appendLine()
            appendLine("课程：${context.course}")
            if (!context.courseCode.isNullOrBlank()) appendLine("课程代码：${context.courseCode}")
            appendLine("作业标题：${context.title}")
            appendLine("开始时间：${context.openedAt ?: "-"}")
            appendLine("截止时间：${context.dueAt ?: "-"}")
            appendLine("提交状态：${context.submittedAt ?: "未提交"}")
            appendLine()
            appendLine("作业要求：")
            appendLine(context.requirementText?.ifBlank { "未提供" } ?: "未提供")
            if (!context.userInstruction.isNullOrBlank()) {
                appendLine()
                appendLine("用户补充要求：")
                appendLine(context.userInstruction)
            }
            if (context.attachments.isNotEmpty()) {
                appendLine()
                appendLine("用户提供的附件已复制到 inbox/：")
                context.attachments.forEach { appendLine("- ${it.displayName}: ${it.relativePath}") }
            }
        }

    private fun systemPrompt(): String =
        """
        你是 BJTU MIS Android 端的本地作业 Agent。必须遵守：
        - 只在当前 task workspace 中读写文件；不要请求 shell、Node.js、npm、pip install 或系统命令。
        - 默认不要上传原始附件内容；需要时先用工具提取文本或读取用户明确提供的文本文件。
        - 逐步说明进展，必要时调用工具生成 output/ 下的答案文件。
        - 最终答案必须提醒用户核对后自行提交，不得声称已经提交。
        - 如果工具失败，尝试用已有信息继续；无法继续时给出清晰原因。
        """.trimIndent()

    private fun encodeToolCalls(calls: List<LlmToolCall>): String =
        buildJsonObject {
            put("tool_calls", JsonArray(calls.map { call ->
                buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    put("arguments", call.arguments)
                }
            }))
        }.toString()

    private fun decodeToolCalls(metadataJson: String?): List<LlmToolCall> {
        if (metadataJson.isNullOrBlank()) return emptyList()
        return runCatching {
            AppJson.parseToJsonElement(metadataJson).jsonObject["tool_calls"]?.jsonArray.orEmpty().mapNotNull { element ->
                val obj = element.jsonObject
                LlmToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            }
        }.getOrDefault(emptyList())
    }

    private class CancellationException : RuntimeException("Agent task canceled")
}
