package cn.edu.bjtu.mis.data.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArchiveTool(
    private val workspaceManager: WorkspaceManager,
) {
    fun tools(): List<AgentTool> = listOf(ExtractTool(), CreateZipTool())

    private inner class ExtractTool : AgentTool {
        override val name = "archive.extract"
        override val description = "Extract a ZIP archive into a work/ directory."
        override val parameters = objectSchema(
            "archivePath" to stringSchema("Relative path to a ZIP file."),
            "targetDir" to stringSchema("Relative target directory under work/."),
            required = listOf("archivePath", "targetDir"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val archive = workspaceManager.resolveRead(taskId, arguments.requiredString("archivePath"))
            if (!archive.isFile) return@withContext ToolResult(errorOutput("not_found", "ZIP 文件不存在"))
            val targetDir = arguments.requiredString("targetDir").trimEnd('/')
            if (!targetDir.startsWith("work/") && targetDir != "work") {
                return@withContext ToolResult(errorOutput("invalid_target", "ZIP 只能解压到 work/ 下"))
            }
            var entries = 0
            var totalBytes = 0L
            val extracted = mutableListOf<String>()
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    if (entries > MAX_ENTRIES) throw IOException("ZIP entry 数超过 $MAX_ENTRIES")
                    val entryName = normalizeZipEntry(entry)
                    val target = workspaceManager.resolveWrite(taskId, "$targetDir/$entryName")
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        var fileBytes = 0L
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                fileBytes += count
                                totalBytes += count
                                if (fileBytes > MAX_FILE_BYTES) throw IOException("ZIP 单文件超过 50 MiB 限制")
                                if (totalBytes > MAX_TOTAL_BYTES) throw IOException("ZIP 解压总大小超过 256 MiB 限制")
                                output.write(buffer, 0, count)
                            }
                        }
                        extracted += workspaceManager.relativePath(taskId, target)
                    }
                    zip.closeEntry()
                }
            }
            workspaceManager.ensureWorkspaceLimit(workspaceManager.root(taskId))
            ToolResult(buildJsonObject {
                put("ok", true)
                put("entries", entries)
                put("bytes", totalBytes)
                put("files", JsonArray(extracted.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            })
        }
    }

    private inner class CreateZipTool : AgentTool {
        override val name = "archive.create_zip"
        override val description = "Create a ZIP archive from a workspace directory."
        override val parameters = objectSchema(
            "sourceDir" to stringSchema("Relative source directory."),
            "zipPath" to stringSchema("Relative destination path under output/ or work/."),
            required = listOf("sourceDir", "zipPath"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val sourceDir = workspaceManager.resolveRead(taskId, arguments.requiredString("sourceDir"))
            if (!sourceDir.isDirectory) return@withContext ToolResult(errorOutput("not_directory", "sourceDir 不是目录"))
            val zipFile = workspaceManager.resolveWrite(taskId, arguments.requiredString("zipPath"))
            zipFile.parentFile?.mkdirs()
            var count = 0
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.canonicalFile != zipFile.canonicalFile }
                    .forEach { file ->
                        count += 1
                        if (count > MAX_ENTRIES) throw IOException("ZIP entry 数超过 $MAX_ENTRIES")
                        val relative = file.relativeTo(sourceDir).path.replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry(relative))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("path", workspaceManager.relativePath(taskId, zipFile))
                    put("entries", count)
                    put("size_bytes", zipFile.length())
                },
                artifacts = listOf(ToolArtifact(workspaceManager.relativePath(taskId, zipFile), "application/zip", "output", zipFile.length())),
            )
        }
    }

    private fun normalizeZipEntry(entry: ZipEntry): String {
        val name = entry.name.replace('\\', '/').trim().trim('/')
        if (name.isBlank() || name.startsWith("/") || name.contains("://") || Regex("""^[A-Za-z]:""").containsMatchIn(name)) {
            throw WorkspaceSecurityException("ZIP entry 路径非法")
        }
        if (name.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw WorkspaceSecurityException("ZIP entry 包含越界路径")
        }
        return name
    }

    private companion object {
        const val MAX_ENTRIES = 500
        const val MAX_FILE_BYTES = 50L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
    }
}
