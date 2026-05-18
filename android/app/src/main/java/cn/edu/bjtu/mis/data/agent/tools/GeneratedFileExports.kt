package cn.edu.bjtu.mis.data.agent.tools

import java.io.File

data class AgentGeneratedFile(
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val role: String,
    val sizeBytes: Long,
)

fun listGeneratedFilesInWorkspace(workspaceRoot: File): List<AgentGeneratedFile> {
    val root = workspaceRoot.canonicalFile
    val files = mutableListOf<AgentGeneratedFile>()
    val outputDir = File(root, "output")

    if (outputDir.isDirectory) {
        outputDir.walkTopDown()
            .filter { it.isFile }
            .mapNotNullTo(files) { file ->
                file.toGeneratedFileOrNull(root = root, role = "output")
            }
    }

    File(root, "results.zip")
        .takeIf { it.isFile }
        ?.toGeneratedFileOrNull(root = root, role = "package")
        ?.let { files += it }

    return files
        .distinctBy { it.relativePath }
        .sortedWith(
            compareBy<AgentGeneratedFile> { if (it.role == "package") 1 else 0 }
                .thenBy { it.relativePath.count { char -> char == '/' } }
                .thenBy { it.relativePath },
        )
}

fun guessAgentGeneratedFileMimeType(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "zip" -> "application/zip"
        "md", "markdown" -> "text/markdown"
        "txt", "log" -> "text/plain"
        "json", "jsonl", "jsonc" -> "application/json"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "html", "htm" -> "text/html"
        "xml" -> "application/xml"
        "yaml", "yml" -> "application/yaml"
        "js", "mjs", "cjs" -> "text/javascript"
        "ts", "tsx", "jsx", "py", "java", "kt", "kts", "c", "cpp", "h", "hpp",
        "cs", "go", "rs", "rb", "php", "sh", "bash", "sql", "css", "scss" -> "text/plain"
        else -> "application/octet-stream"
    }
}

fun agentGeneratedFilePreviewKind(relativePath: String, mimeType: String): String? {
    val normalizedMimeType = mimeType.lowercase()
    val extension = relativePath.substringAfterLast('.', "").lowercase()
    return when {
        normalizedMimeType == "application/pdf" || extension == "pdf" -> "pdf"
        normalizedMimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            extension == "docx" -> "docx"
        normalizedMimeType == "text/markdown" || extension in setOf("md", "markdown") -> "markdown"
        extension in CODE_EXTENSIONS -> "code"
        normalizedMimeType.startsWith("text/") ||
            normalizedMimeType in TEXT_MIME_TYPES ||
            extension in TEXT_EXTENSIONS -> "text"
        else -> null
    }
}

private fun File.toGeneratedFileOrNull(root: File, role: String): AgentGeneratedFile? {
    val target = canonicalFile
    if (target != root && !target.path.startsWith(root.path + File.separator)) {
        return null
    }
    val relativePath = target.relativeTo(root).path.replace(File.separatorChar, '/')
    return AgentGeneratedFile(
        displayName = name,
        relativePath = relativePath,
        mimeType = guessAgentGeneratedFileMimeType(name),
        role = role,
        sizeBytes = length(),
    )
}

private val TEXT_MIME_TYPES = setOf(
    "application/json",
    "application/xml",
    "application/yaml",
    "application/x-yaml",
    "application/javascript",
)

private val TEXT_EXTENSIONS = setOf(
    "txt",
    "log",
    "json",
    "jsonl",
    "jsonc",
    "csv",
    "tsv",
    "html",
    "htm",
    "xml",
    "yaml",
    "yml",
)

private val CODE_EXTENSIONS = setOf(
    "js",
    "mjs",
    "cjs",
    "ts",
    "tsx",
    "jsx",
    "py",
    "java",
    "kt",
    "kts",
    "c",
    "cpp",
    "h",
    "hpp",
    "cs",
    "go",
    "rs",
    "rb",
    "php",
    "sh",
    "bash",
    "sql",
    "css",
    "scss",
)
