package cn.edu.bjtu.mis.data.agent.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cn.edu.bjtu.mis.data.agent.model.AgentAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class WorkspaceSecurityException(message: String) : IOException(message)

data class WorkspaceImportSource(
    val file: File,
    val displayName: String,
    val mimeType: String? = null,
)

class WorkspaceManager(
    private val context: Context,
    private val maxWorkspaceBytes: Long = DEFAULT_MAX_WORKSPACE_BYTES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    fun root(taskId: String): File =
        File(context.filesDir, "agent-workspaces/$taskId")

    fun prepare(taskId: String): File {
        val root = root(taskId)
        listOf("inbox", "work", "output", "logs").forEach { File(root, it).mkdirs() }
        return root
    }

    fun delete(taskId: String) {
        root(taskId).deleteRecursively()
    }

    suspend fun importUris(taskId: String, uris: List<Uri>): List<AgentAttachment> = withContext(Dispatchers.IO) {
        val root = prepare(taskId)
        val inbox = File(root, "inbox").apply { mkdirs() }
        uris.mapIndexed { index, uri ->
            val description = describe(uri)
            val name = safeFileName(description.name.ifBlank { "attachment-${index + 1}" })
            val target = uniqueFile(inbox, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    var written = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > maxFileBytes) throw IOException("附件 ${description.name} 超过 50 MiB 限制")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("无法读取附件 ${description.name}")
            ensureWorkspaceLimit(root)
            AgentAttachment(
                displayName = description.name,
                relativePath = "inbox/${target.name}",
                mimeType = description.mimeType,
                sizeBytes = target.length(),
            )
        }
    }

    suspend fun importFiles(taskId: String, sources: List<WorkspaceImportSource>): List<AgentAttachment> = withContext(Dispatchers.IO) {
        val root = prepare(taskId)
        val inbox = File(root, "inbox").apply { mkdirs() }
        sources.mapIndexed { index, source ->
            val displayName = source.displayName.ifBlank { source.file.name.ifBlank { "attachment-${index + 1}" } }
            val name = safeFileName(displayName)
            val target = uniqueFile(inbox, name)
            var written = 0L
            try {
                source.file.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > maxFileBytes) {
                                throw IOException("附件 $displayName 超过 50 MiB 限制")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                ensureWorkspaceLimit(root)
            } catch (error: Exception) {
                target.delete()
                throw error
            }
            AgentAttachment(
                displayName = displayName,
                relativePath = "inbox/${target.name}",
                mimeType = source.mimeType,
                sizeBytes = target.length(),
            )
        }
    }

    fun resolveRead(taskId: String, relativePath: String): File =
        resolve(taskId, relativePath, writable = false)

    fun resolveWrite(taskId: String, relativePath: String): File =
        resolve(taskId, relativePath, writable = true)

    fun ensureWorkspaceLimit(taskRoot: File) {
        val size = taskRoot.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        if (size > maxWorkspaceBytes) {
            throw IOException("Agent workspace 超过 ${maxWorkspaceBytes / 1024 / 1024} MiB 限制")
        }
    }

    fun relativePath(taskId: String, file: File): String {
        val root = root(taskId).canonicalFile
        val target = file.canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw WorkspaceSecurityException("文件不在当前 Agent workspace 中")
        }
        return target.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/')
    }

    private fun resolve(taskId: String, relativePath: String, writable: Boolean): File {
        val parts = validateAgentRelativePath(relativePath, writable)
        val taskRoot = prepare(taskId).canonicalFile
        val target = File(taskRoot, parts.joinToString(File.separator)).canonicalFile
        if (target != taskRoot && !target.path.startsWith(taskRoot.path + File.separator)) {
            throw WorkspaceSecurityException("路径越界")
        }
        var cursor: File? = target
        while (cursor != null && cursor != taskRoot.parentFile) {
            if (java.nio.file.Files.isSymbolicLink(cursor.toPath())) {
                throw WorkspaceSecurityException("不允许符号链接路径")
            }
            cursor = cursor.parentFile
        }
        return target
    }

    private fun describe(uri: Uri): AttachmentDescription {
        var name: String? = null
        var size: Long? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return AttachmentDescription(
            name = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment",
            mimeType = context.contentResolver.getType(uri),
            size = size,
        )
    }

    private fun safeFileName(value: String): String =
        value.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .take(120)
            .ifBlank { "attachment" }

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

    private data class AttachmentDescription(
        val name: String,
        val mimeType: String?,
        val size: Long?,
    )

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 50L * 1024L * 1024L
        const val DEFAULT_MAX_WORKSPACE_BYTES = 256L * 1024L * 1024L
    }
}

internal fun validateAgentRelativePath(relativePath: String, writable: Boolean): List<String> {
    val normalized = relativePath.replace('\\', '/').trim()
    if (normalized.isBlank()) throw WorkspaceSecurityException("路径不能为空")
    if (normalized == ".") {
        if (writable) {
            throw WorkspaceSecurityException("Root workspace path is read-only")
        }
        return emptyList()
    }
    if (normalized.startsWith("/") || normalized.contains("://")) {
        throw WorkspaceSecurityException("只允许相对路径")
    }
    if (Regex("""^[A-Za-z]:""").containsMatchIn(normalized)) {
        throw WorkspaceSecurityException("不允许 Windows drive path")
    }
    val parts = normalized.split('/').filter { it.isNotBlank() }
    if (parts.any { it == "." || it == ".." }) {
        throw WorkspaceSecurityException("路径不能包含 . 或 ..")
    }
    if (writable && parts.firstOrNull() !in setOf("work", "output", "logs")) {
        throw WorkspaceSecurityException("只允许写入 work/、output/ 或 logs/")
    }
    return parts
}
