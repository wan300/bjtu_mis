package cn.edu.bjtu.mis.data.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTool(
    private val workspaceManager: WorkspaceManager,
) {
    fun tools(): List<AgentTool> = listOf(ExtractTool(), CreateZipTool())

    private inner class ExtractTool : AgentTool {
        override val name = "archive.extract"
        override val description =
            "Extract a supported archive or compressed file into a work/ directory. Supports zip, jar, tar, tar.gz, tgz, tar.bz2, tbz2, gz, and bz2."
        override val parameters = objectSchema(
            "archivePath" to stringSchema("Relative path to a supported archive or compressed file."),
            "targetDir" to stringSchema("Relative target directory under work/."),
            required = listOf("archivePath", "targetDir"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val archivePath = arguments.requiredString("archivePath")
            val archive = workspaceManager.resolveRead(taskId, archivePath)
            if (!archive.isFile) return@withContext ToolResult(errorOutput("not_found", "Archive file does not exist"))
            val targetDir = arguments.requiredString("targetDir").trimEnd('/')
            if (!targetDir.startsWith("work/") && targetDir != "work") {
                return@withContext ToolResult(errorOutput("invalid_target", "Archives can only be extracted under work/"))
            }
            val extraction = try {
                extractArchiveToWorkspace(workspaceManager, taskId, archivePath, targetDir)
            } catch (error: UnsupportedArchiveException) {
                return@withContext ToolResult(errorOutput("unsupported_archive", error.message ?: "Unsupported archive format"))
            }
            ToolResult(buildJsonObject {
                put("ok", true)
                put("format", extraction.format)
                put("entries", extraction.entries)
                put("bytes", extraction.bytes)
                put("files", extraction.filesJsonArray())
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
            if (!sourceDir.isDirectory) return@withContext ToolResult(errorOutput("not_directory", "sourceDir is not a directory"))
            val zipFile = workspaceManager.resolveWrite(taskId, arguments.requiredString("zipPath"))
            zipFile.parentFile?.mkdirs()
            var count = 0
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.canonicalFile != zipFile.canonicalFile }
                    .forEach { file ->
                        count += 1
                        if (count > MAX_ZIP_ENTRIES) throw IOException("ZIP entry count exceeds $MAX_ZIP_ENTRIES")
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
}
