package cn.edu.bjtu.mis.openwebui

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.agent.model.AgentAttachment
import cn.edu.bjtu.mis.data.agent.tools.AgentGeneratedFile
import cn.edu.bjtu.mis.data.agent.tools.ToolArtifact
import cn.edu.bjtu.mis.data.agent.tools.WorkspaceImportSource
import cn.edu.bjtu.mis.data.agent.tools.agentGeneratedFilePreviewKind
import cn.edu.bjtu.mis.data.agent.tools.guessAgentGeneratedFileMimeType
import cn.edu.bjtu.mis.data.agent.tools.listGeneratedFilesInWorkspace
import cn.edu.bjtu.mis.data.sync.SessionKeepAliveForegroundService
import cn.edu.bjtu.mis.model.HomeworkAttachment
import cn.edu.bjtu.mis.model.HomeworkItem
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.util.UUID

@CapacitorPlugin(name = "NativeAgentTools")
class NativeAgentToolsPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PluginMethod
    fun consumePendingHomeworkDraft(call: PluginCall) {
        val handoff = NativeAgentHomeworkHandoffStore.consume()
        if (handoff == null) {
            call.resolve(JSObject().put("hasPending", false))
            return
        }

        scope.launch {
            try {
                call.resolve(prepareHomeworkDraft(handoff))
            } catch (error: Exception) {
                call.reject(error.message ?: "Failed to prepare homework draft", error)
            }
        }
    }

    @PluginMethod
    fun listTools(call: PluginCall) {
        try {
            val tools = container().agentToolRegistry.allTools
            val items = JSONArray()
            tools.forEach { tool ->
                val exposedName = nativeToExposedToolName[tool.name] ?: return@forEach
                val function = JSObject()
                    .put("name", exposedName)
                    .put("description", tool.description)
                    .put("parameters", JSONObject(tool.parameters.toString()))
                items.put(
                    JSObject()
                        .put("type", "function")
                        .put("requiresWorkspace", tool.requiresWorkspace)
                        .put("function", function)
                )
            }
            call.resolve(JSObject().put("tools", items))
        } catch (error: Exception) {
            call.reject(error.message ?: "Failed to list native agent tools", error)
        }
    }

    @PluginMethod
    fun executeTool(call: PluginCall) {
        val workspaceId = call.getString("workspaceId")?.trim().orEmpty()
        val toolName = call.getString("toolName")?.trim().orEmpty()
        if (toolName.isBlank()) {
            call.reject("toolName is required")
            return
        }

        val nativeName = exposedToNativeToolName[toolName]
        if (nativeName == null) {
            call.reject("Unknown native agent tool: $toolName")
            return
        }

        val argumentsJson = call.getObject("arguments")?.toString()
            ?: call.getString("arguments")
            ?: "{}"

        scope.launch {
            try {
                val arguments = AppJson.parseToJsonElement(argumentsJson).jsonObject
                val tool = container().agentToolRegistry.get(nativeName)
                    ?: throw IllegalArgumentException("Native agent tool not registered: $nativeName")
                if (tool.requiresWorkspace && workspaceId.isBlank()) {
                    throw IllegalArgumentException("workspaceId is required")
                }
                val result = tool.execute(workspaceId, arguments)
                val response = JSObject()
                    .put("toolName", toolName)
                    .put("nativeToolName", nativeName)
                    .put("output", JSONObject(result.output.toString()))
                    .put("artifacts", artifactsToJson(result.artifacts))
                result.stdout?.let { response.put("stdout", it) }
                result.stderr?.let { response.put("stderr", it) }
                call.resolve(response)
            } catch (error: Exception) {
                call.reject(error.message ?: "Native agent tool failed", error)
            }
        }
    }

    @PluginMethod
    fun beginKeepAlive(call: PluginCall) {
        val token = call.getString("token")?.trim().orEmpty()
        if (token.isBlank()) {
            call.reject("token is required")
            return
        }

        val reason = call.getString("reason")?.trim()?.takeIf { it.isNotBlank() }
            ?: SessionKeepAliveForegroundService.REASON_AGENT
        SessionKeepAliveForegroundService.acquire(getContext(), reason, token)
        call.resolve(JSObject().put("active", true))
    }

    @PluginMethod
    fun endKeepAlive(call: PluginCall) {
        val token = call.getString("token")?.trim().orEmpty()
        if (token.isBlank()) {
            call.reject("token is required")
            return
        }

        SessionKeepAliveForegroundService.release(getContext(), token)
        call.resolve(JSObject().put("active", false))
    }

    @PluginMethod
    fun listGeneratedFiles(call: PluginCall) {
        val workspaceId = call.getString("workspaceId")?.trim().orEmpty()
        if (workspaceId.isBlank()) {
            call.reject("workspaceId is required")
            return
        }

        scope.launch {
            try {
                val root = container().agentWorkspaceManager.root(workspaceId)
                call.resolve(JSObject().put("files", generatedFilesToJson(listGeneratedFilesInWorkspace(root))))
            } catch (error: Exception) {
                call.reject(error.message ?: "Failed to list generated files", error)
            }
        }
    }

    @PluginMethod
    fun saveGeneratedFile(call: PluginCall) {
        val workspaceId = call.getString("workspaceId")?.trim().orEmpty()
        val relativePath = call.getString("relativePath")?.trim().orEmpty()
        if (workspaceId.isBlank()) {
            call.reject("workspaceId is required")
            return
        }
        if (relativePath.isBlank()) {
            call.reject("relativePath is required")
            return
        }

        scope.launch {
            try {
                val file = resolveWorkspaceFile(workspaceId, relativePath)
                val mimeType = guessAgentGeneratedFileMimeType(file.name)
                val saved = saveFileToDownloads(file, mimeType)
                call.resolve(
                    JSObject()
                        .put("saved", true)
                        .put("uri", saved.uri)
                        .put("displayName", saved.displayName)
                        .put("location", saved.location)
                        .put("mimeType", mimeType)
                        .put("sizeBytes", saved.sizeBytes)
                )
            } catch (error: Exception) {
                call.reject(error.message ?: "Failed to save generated file", error)
            }
        }
    }

    @PluginMethod
    fun readGeneratedFilePreview(call: PluginCall) {
        val workspaceId = call.getString("workspaceId")?.trim().orEmpty()
        val relativePath = call.getString("relativePath")?.trim().orEmpty()
        if (workspaceId.isBlank()) {
            call.reject("workspaceId is required")
            return
        }
        if (relativePath.isBlank()) {
            call.reject("relativePath is required")
            return
        }

        scope.launch {
            try {
                val file = resolveWorkspaceFile(workspaceId, relativePath)
                val mimeType = guessAgentGeneratedFileMimeType(file.name)
                val kind = agentGeneratedFilePreviewKind(relativePath, mimeType)
                if (kind == null) {
                    call.resolve(
                        JSObject()
                            .put("previewable", false)
                            .put("reason", "unsupported_type")
                            .put("mimeType", mimeType)
                    )
                    return@launch
                }
                if (file.length() > PREVIEW_MAX_BYTES) {
                    call.resolve(
                        JSObject()
                            .put("previewable", false)
                            .put("reason", "too_large")
                            .put("mimeType", mimeType)
                            .put("sizeBytes", file.length())
                    )
                    return@launch
                }

                val response = JSObject()
                    .put("previewable", true)
                    .put("kind", kind)
                    .put("displayName", file.name)
                    .put("relativePath", relativePath)
                    .put("mimeType", mimeType)
                    .put("sizeBytes", file.length())

                if (kind == "pdf" || kind == "docx") {
                    response.put("encoding", "base64")
                    response.put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                } else {
                    response.put("encoding", "utf-8")
                    response.put("text", file.readText(Charsets.UTF_8))
                }

                call.resolve(response)
            } catch (error: Exception) {
                call.reject(error.message ?: "Failed to read generated file preview", error)
            }
        }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }

    private suspend fun prepareHomeworkDraft(handoff: NativeAgentHomeworkHandoff): JSObject {
        val workspaceId = UUID.randomUUID().toString()
        val appContainer = container()
        appContainer.agentWorkspaceManager.prepare(workspaceId)

        val imported = mutableListOf<AgentAttachment>()
        val failed = mutableListOf<AttachmentFailure>()
        val homework = handoff.homework
        val homeworkId = homework.homeworkId
        var importedBytes = 0L

        for (attachment in homework.attachments) {
            if (homeworkId == null) {
                failed += AttachmentFailure(attachment.filename, "作业 ID 不可用，无法下载附件")
                continue
            }
            if (imported.size >= HOMEWORK_HANDOFF_MAX_AUTO_IMPORT_COUNT) {
                failed += AttachmentFailure(
                    attachment.filename,
                    "超过默认自动导入上限（最多 $HOMEWORK_HANDOFF_MAX_AUTO_IMPORT_COUNT 个或 20 MiB），请按需手动下载。",
                )
                continue
            }

            runCatching {
                val mimeType = guessMimeType(attachment)
                val downloaded = appContainer.homeworkAttachmentRepository.download(
                    homeworkId = homeworkId,
                    attachmentId = attachment.attachmentId,
                    filename = attachment.filename,
                )
                val sizeBytes = downloaded.length().coerceAtLeast(0L)
                if (importedBytes + sizeBytes > HOMEWORK_HANDOFF_MAX_AUTO_IMPORT_BYTES) {
                    throw IOException("超过默认自动导入大小上限（20 MiB），请按需手动下载。")
                }
                val importedAttachment = appContainer.agentWorkspaceManager.importFiles(
                    taskId = workspaceId,
                    sources = listOf(
                        WorkspaceImportSource(
                            file = downloaded,
                            displayName = attachment.filename,
                            mimeType = mimeType,
                        )
                    ),
                ).single()
                sizeBytes to importedAttachment
            }.onSuccess { (sizeBytes, attachmentInfo) ->
                imported += attachmentInfo
                importedBytes += sizeBytes
            }.onFailure { error ->
                failed += AttachmentFailure(
                    filename = attachment.filename,
                    message = error.message ?: "附件下载或导入失败",
                )
            }
        }

        return JSObject()
            .put("hasPending", true)
            .put("workspaceId", workspaceId)
            .put("draft", buildHomeworkDraft(homework, handoff.userInstruction, workspaceId, imported, failed))
            .put("attachments", attachmentsToJson(imported))
            .put("failedAttachments", failuresToJson(failed))
    }

    private fun buildHomeworkDraft(
        homework: HomeworkItem,
        userInstruction: String,
        workspaceId: String,
        attachments: List<AgentAttachment>,
        failedAttachments: List<AttachmentFailure>,
    ): String = buildString {
        appendLine("请协助分析以下北京交通大学课程平台作业。")
        appendLine("注意：你只能生成分析、步骤、答案草稿或 output/ 下的文件，不能自动提交作业。")
        appendLine()
        appendLine("课程：${homework.course}")
        homework.courseCode?.takeIf { it.isNotBlank() }?.let { appendLine("课程代码：$it") }
        appendLine("作业标题：${homework.title}")
        appendLine("开始时间：${homework.openedAt ?: "-"}")
        appendLine("截止时间：${homework.dueAt ?: "-"}")
        appendLine("提交状态：${homework.submittedAt ?: "未提交"}")
        appendLine("Agent workspace：$workspaceId")
        appendLine()
        appendLine("作业要求：")
        appendLine(homework.requirementText?.takeIf { it.isNotBlank() } ?: homework.contentExcerpt?.takeIf { it.isNotBlank() } ?: "未提供")
        if (userInstruction.isNotBlank()) {
            appendLine()
            appendLine("用户补充要求：")
            appendLine(userInstruction)
        }
        if (attachments.isNotEmpty()) {
            appendLine()
            appendLine("已自动下载并导入的附件，可通过 agent_file_list、agent_file_read、agent_document_extract_pdf/docx、agent_archive_extract 等工具访问。")
            appendLine("工具调用必须使用模型的 native tool_calls JSON 参数，不要在正文输出 XML、DSML、<tool_calls> 或 <agent_...> 标签。")
            appendLine("建议流程和参数示例：")
            appendLine("- agent_file_list: {\"path\":\"inbox\"}")
            appendLine("- agent_archive_extract: {\"archivePath\":\"inbox/example.zip\",\"targetDir\":\"work/attachments/example\"}")
            appendLine("- agent_document_extract_pdf: {\"path\":\"inbox/example.pdf\",\"outputPath\":\"work/example.md\"}")
            appendLine("- agent_document_extract_docx: {\"path\":\"inbox/example.docx\",\"outputPath\":\"work/example.md\"}")
            attachments.forEach { attachment ->
                appendLine("- ${attachment.displayName}: 原始附件 ${attachment.relativePath}")
                appendLine("  - 如需解压，建议 targetDir：work/attachments/${safeAttachmentDirName(attachment)}")
            }
        }
        if (failedAttachments.isNotEmpty()) {
            appendLine()
            appendLine("以下附件未能自动下载或导入，请在分析时说明限制：")
            failedAttachments.forEach { appendLine("- ${it.filename}: ${it.message}") }
        }
    }.trim()

    private fun container() =
        (getContext().applicationContext as? BjtuMisApplication)?.container
            ?: error("BJTU MIS application container is unavailable")

    private fun guessMimeType(attachment: HomeworkAttachment): String? =
        URLConnection.guessContentTypeFromName(attachment.filename)

    private fun safeAttachmentDirName(attachment: AgentAttachment): String =
        safeAttachmentDirName(
            displayName = attachment.displayName,
            stableId = attachment.relativePath.substringAfterLast('/'),
        )

    private fun safeAttachmentDirName(displayName: String, stableId: String): String {
        val baseName = displayName
            .substringBeforeLast('.', displayName)
            .trim()
            .replace(Regex("""[\u0000-\u001F\\/:*?"<>|]+"""), "_")
            .trim('.', '_', ' ')
            .ifBlank { "attachment" }
            .take(80)
        val suffix = stableId
            .trim()
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .trim('.', '_')
            .take(24)
            .ifBlank { "archive" }
        return "${baseName}_$suffix"
    }

    private fun resolveWorkspaceFile(workspaceId: String, relativePath: String): File {
        val file = container().agentWorkspaceManager.resolveRead(workspaceId, relativePath)
        if (!file.isFile) {
            throw IOException("File not found: $relativePath")
        }
        return file
    }

    private fun saveFileToDownloads(file: File, mimeType: String): SavedGeneratedFile =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveFileToPublicDownloads(file, mimeType)
        } else {
            saveFileToAppDownloads(file, mimeType)
        }

    private fun saveFileToPublicDownloads(file: File, mimeType: String): SavedGeneratedFile {
        val displayName = safeDownloadFileName(file.name)
        val resolver = getContext().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BJTU MIS")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create Downloads entry")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Failed to open Downloads entry")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return SavedGeneratedFile(
                uri = uri.toString(),
                displayName = displayName,
                location = "Downloads/BJTU MIS/$displayName",
                sizeBytes = file.length(),
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveFileToAppDownloads(file: File, mimeType: String): SavedGeneratedFile {
        val displayName = safeDownloadFileName(file.name)
        val dir = (getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(getContext().filesDir, "downloads"))
            .resolve("BJTU MIS")
            .apply { mkdirs() }
        val target = uniqueFile(dir, displayName)
        file.copyTo(target, overwrite = false)
        return SavedGeneratedFile(
            uri = Uri.fromFile(target).toString(),
            displayName = target.name,
            location = target.absolutePath,
            sizeBytes = target.length(),
            mimeType = mimeType,
        )
    }

    private fun safeDownloadFileName(value: String): String =
        value.trim()
            .replace(Regex("""[\u0000-\u001F\\/:*?"<>|]+"""), "_")
            .trim('.', '_', ' ')
            .take(120)
            .ifBlank { "generated-file" }

    private fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var candidate = File(dir, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem-$index$extension")
            index += 1
        }
        return candidate
    }

    private fun generatedFilesToJson(files: List<AgentGeneratedFile>): JSONArray =
        JSONArray().apply {
            files.forEach { file ->
                put(
                    JSObject()
                        .put("displayName", file.displayName)
                        .put("relativePath", file.relativePath)
                        .put("mimeType", file.mimeType)
                        .put("role", file.role)
                        .put("sizeBytes", file.sizeBytes)
                )
            }
        }

    private fun attachmentsToJson(attachments: List<AgentAttachment>): JSONArray =
        JSONArray().apply {
            attachments.forEach { attachment ->
                val item = JSObject()
                    .put("displayName", attachment.displayName)
                    .put("relativePath", attachment.relativePath)
                    .put("mimeType", attachment.mimeType)
                    .put("sizeBytes", attachment.sizeBytes ?: 0L)
                put(item)
            }
        }

    private fun failuresToJson(failures: List<AttachmentFailure>): JSONArray =
        JSONArray().apply {
            failures.forEach { failure ->
                put(
                    JSObject()
                        .put("filename", failure.filename)
                        .put("message", failure.message)
                )
            }
        }

    private fun artifactsToJson(artifacts: List<ToolArtifact>): JSONArray =
        JSONArray().apply {
            artifacts.forEach { artifact ->
                put(
                    JSObject()
                        .put("relativePath", artifact.relativePath)
                        .put("mimeType", artifact.mimeType)
                        .put("role", artifact.role)
                        .put("sizeBytes", artifact.sizeBytes)
                )
            }
        }

    private data class AttachmentFailure(
        val filename: String,
        val message: String,
    )

    private data class SavedGeneratedFile(
        val uri: String,
        val displayName: String,
        val location: String,
        val sizeBytes: Long,
        val mimeType: String? = null,
    )

    private companion object {
        const val PREVIEW_MAX_BYTES = 10L * 1024L * 1024L
        const val HOMEWORK_HANDOFF_MAX_AUTO_IMPORT_COUNT = 5
        const val HOMEWORK_HANDOFF_MAX_AUTO_IMPORT_BYTES = 20L * 1024L * 1024L

        val exposedToNativeToolName = linkedMapOf(
            "agent_file_list" to "file.list",
            "agent_file_read" to "file.read",
            "agent_file_write" to "file.write",
            "agent_file_delete" to "file.delete",
            "agent_archive_extract" to "archive.extract",
            "agent_archive_create_zip" to "archive.create_zip",
            "agent_document_extract_pdf" to "document.extract_pdf",
            "agent_document_extract_docx" to "document.extract_docx",
            "agent_document_generate_pdf" to "document.generate_pdf",
            "agent_document_generate_docx" to "document.generate_docx",
            "agent_run_javascript" to "code.run_js",
            "agent_mail_list_folders" to "mail.list_folders",
            "agent_mail_list_recent" to "mail.list_recent",
            "agent_mail_read" to "mail.read",
            "agent_mail_mark_read" to "mail.mark_read",
            "agent_mail_digest_context" to "mail.digest_context",
            "agent_mail_search_contacts" to "mail.search_contacts",
            "agent_mail_save_draft" to "mail.save_draft",
            "agent_mail_send" to "mail.send",
            "agent_package_results" to "package.results",
        )
        val nativeToExposedToolName = exposedToNativeToolName.entries.associate { (exposed, native) ->
            native to exposed
        }
    }
}
