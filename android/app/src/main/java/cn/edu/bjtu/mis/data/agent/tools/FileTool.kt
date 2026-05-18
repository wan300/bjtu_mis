package cn.edu.bjtu.mis.data.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

class FileTool(
    private val workspaceManager: WorkspaceManager,
) {
    fun tools(): List<AgentTool> = listOf(ListTool(), ReadTool(), WriteTool(), DeleteTool())

    private inner class ListTool : AgentTool {
        override val name = "file.list"
        override val description = "List files inside the current Agent workspace."
        override val parameters = objectSchema(
            "path" to stringSchema("Relative directory path such as inbox, work, or output."),
            "recursive" to booleanSchema(false),
            required = listOf("path"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val dir = workspaceManager.resolveRead(taskId, arguments.requiredString("path"))
            if (!dir.exists()) return@withContext ToolResult(errorOutput("not_found", "路径不存在"))
            if (!dir.isDirectory) return@withContext ToolResult(errorOutput("not_directory", "路径不是目录"))
            val root = workspaceManager.root(taskId).canonicalFile
            val files = if (arguments.boolean("recursive")) dir.walkTopDown().filter { it != dir }.toList() else dir.listFiles()?.toList().orEmpty()
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("files", JsonArray(files.map { file ->
                        buildJsonObject {
                            put("path", file.canonicalFile.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/'))
                            put("type", if (file.isDirectory) "directory" else "file")
                            put("size_bytes", if (file.isFile) file.length() else 0L)
                        }
                    }))
                }
            )
        }
    }

    private inner class ReadTool : AgentTool {
        override val name = "file.read"
        override val description = "Read a UTF-8 text file from the current Agent workspace."
        override val parameters = objectSchema(
            "path" to stringSchema("Relative file path."),
            required = listOf("path"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val file = workspaceManager.resolveRead(taskId, arguments.requiredString("path"))
            if (!file.exists()) return@withContext ToolResult(errorOutput("not_found", "文件不存在"))
            if (!file.isFile) return@withContext ToolResult(errorOutput("not_file", "路径不是文件"))
            if (isSupportedArchive(file, filename = file.name)) {
                return@withContext ToolResult(
                    errorOutput(
                        "unsupported_file_type",
                        "This file is an archive or compressed file. Use agent_archive_extract to extract it under work/ before reading extracted files.",
                    )
                )
            }
            if (looksBinary(file)) return@withContext ToolResult(errorOutput("unsupported_file_type", "file.read 只支持 UTF-8 文本"))
            val bytes = file.readBytes()
            val truncated = bytes.size > MAX_READ_BYTES
            val text = bytes.copyOfRange(0, minOf(bytes.size, MAX_READ_BYTES)).toString(Charsets.UTF_8)
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("path", arguments.requiredString("path"))
                    put("content", text)
                    put("truncated", truncated)
                    put("size_bytes", bytes.size)
                }
            )
        }
    }

    private inner class WriteTool : AgentTool {
        override val name = "file.write"
        override val description = "Write UTF-8 text into work/ or output/ in the current Agent workspace."
        override val parameters = objectSchema(
            "path" to stringSchema("Relative output path under work/ or output/."),
            "content" to stringSchema("UTF-8 text content."),
            "append" to booleanSchema(false),
            required = listOf("path", "content"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val file = workspaceManager.resolveWrite(taskId, arguments.requiredString("path"))
            file.parentFile?.mkdirs()
            val content = arguments.string("content").orEmpty()
            if (arguments.boolean("append")) file.appendText(content) else file.writeText(content)
            workspaceManager.ensureWorkspaceLimit(workspaceManager.root(taskId))
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("path", workspaceManager.relativePath(taskId, file))
                    put("size_bytes", file.length())
                },
                artifacts = listOf(ToolArtifact(workspaceManager.relativePath(taskId, file), "text/plain", "intermediate", file.length())),
            )
        }
    }

    private inner class DeleteTool : AgentTool {
        override val name = "file.delete"
        override val description = "Delete a single file under work/ or output/."
        override val parameters = objectSchema(
            "path" to stringSchema("Relative file path under work/ or output/."),
            required = listOf("path"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val file = workspaceManager.resolveWrite(taskId, arguments.requiredString("path"))
            if (!file.exists()) return@withContext ToolResult(errorOutput("not_found", "文件不存在"))
            if (!file.isFile) return@withContext ToolResult(errorOutput("not_file", "file.delete 不删除目录"))
            val deleted = file.delete()
            ToolResult(buildJsonObject {
                put("ok", deleted)
                put("deleted", deleted)
                put("path", arguments.requiredString("path"))
            })
        }
    }

    private fun looksBinary(file: File): Boolean {
        val sample = ByteArray(minOf(file.length(), 4096L).toInt())
        if (sample.isEmpty()) return false
        file.inputStream().use { it.read(sample) }
        return sample.any { it == 0.toByte() }
    }

    private companion object {
        const val MAX_READ_BYTES = 128 * 1024
    }
}
