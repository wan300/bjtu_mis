package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.repository.nowIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageTool(
    private val workspaceManager: WorkspaceManager,
) {
    fun tools(): List<AgentTool> = listOf(ResultsTool())

    private inner class ResultsTool : AgentTool {
        override val name = "package.results"
        override val description = "Package final answer, output artifacts, logs, and manifest into results.zip."
        override val parameters = objectSchema(
            "finalAnswer" to stringSchema("Final answer markdown."),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val root = workspaceManager.prepare(taskId)
            val outputDir = File(root, "output").apply { mkdirs() }
            val logsDir = File(root, "logs").apply { mkdirs() }
            val finalAnswer = arguments.string("finalAnswer").orEmpty()
            File(outputDir, "final_answer.md").writeText(finalAnswer)
            val manifest = buildJsonObject {
                put("taskId", taskId)
                put("createdAt", nowIso())
                put("status", "packaged")
                put("artifacts", JsonArray(outputDir.walkTopDown().filter { it.isFile }.map { file ->
                    buildJsonObject {
                        put("path", "artifacts/${file.relativeTo(outputDir).path.replace(File.separatorChar, '/')}")
                        put("sizeBytes", file.length())
                    }
                }.toList()))
            }.toString()
            val manifestFile = File(root, "manifest.json").apply { writeText(manifest) }
            val zipFile = File(root, "results.zip")
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.putText("final_answer.md", finalAnswer)
                outputDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putFile("artifacts/${file.relativeTo(outputDir).path.replace(File.separatorChar, '/')}", file)
                }
                if (logsDir.exists()) {
                    logsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        zip.putFile("logs/${file.relativeTo(logsDir).path.replace(File.separatorChar, '/')}", file)
                    }
                }
                zip.putFile("manifest.json", manifestFile)
            }
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("path", "results.zip")
                    put("size_bytes", zipFile.length())
                },
                artifacts = listOf(ToolArtifact("results.zip", "application/zip", "package", zipFile.length())),
            )
        }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
