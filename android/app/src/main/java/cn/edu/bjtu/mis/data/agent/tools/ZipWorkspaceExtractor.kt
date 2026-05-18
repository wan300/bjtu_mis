package cn.edu.bjtu.mis.data.agent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

data class ArchiveExtractionResult(
    val entries: Int,
    val bytes: Long,
    val files: List<String>,
    val format: String,
)

typealias ZipExtractionResult = ArchiveExtractionResult

class UnsupportedArchiveException(message: String) : IOException(message)

fun isSupportedArchive(file: File, filename: String? = null, mimeType: String? = null): Boolean =
    detectArchiveFormat(file, filename, mimeType) != null

fun detectSupportedArchiveFormat(file: File, filename: String? = null, mimeType: String? = null): String? =
    detectArchiveFormat(file, filename, mimeType)?.id

fun isZipArchive(file: File): Boolean =
    file.isFile && hasZipSignature(file)

fun extractArchiveToWorkspace(
    workspaceManager: WorkspaceManager,
    taskId: String,
    archivePath: String,
    targetDir: String,
): ArchiveExtractionResult {
    val archive = workspaceManager.resolveRead(taskId, archivePath)
    if (!archive.isFile) throw IOException("Archive file does not exist")

    val normalizedTargetDir = targetDir.trim().trimEnd('/')
    if (!normalizedTargetDir.startsWith("work/") && normalizedTargetDir != "work") {
        throw WorkspaceSecurityException("Archives can only be extracted under work/")
    }

    return extractArchive(
        archive = archive,
        filename = archivePath.replace('\\', '/').substringAfterLast('/'),
        resolveTarget = { entryName -> workspaceManager.resolveWrite(taskId, "$normalizedTargetDir/$entryName") },
        relativePath = { file -> workspaceManager.relativePath(taskId, file) },
        ensureLimit = { workspaceManager.ensureWorkspaceLimit(workspaceManager.root(taskId)) },
    )
}

fun extractZipArchiveToWorkspace(
    workspaceManager: WorkspaceManager,
    taskId: String,
    archivePath: String,
    targetDir: String,
): ArchiveExtractionResult =
    extractArchiveToWorkspace(workspaceManager, taskId, archivePath, targetDir)

internal fun extractArchive(
    archive: File,
    filename: String = archive.name,
    mimeType: String? = null,
    resolveTarget: (entryName: String) -> File,
    relativePath: (file: File) -> String,
    ensureLimit: () -> Unit = {},
    maxEntries: Int = MAX_ARCHIVE_ENTRIES,
    maxFileBytes: Long = MAX_ARCHIVE_FILE_BYTES,
    maxTotalBytes: Long = MAX_ARCHIVE_TOTAL_BYTES,
): ArchiveExtractionResult {
    val format = detectArchiveFormat(archive, filename, mimeType)
        ?: throw UnsupportedArchiveException("Unsupported archive format. Supported formats: zip, jar, tar, tar.gz, tgz, tar.bz2, tbz2, gz, bz2.")
    val counters = ExtractionCounters()

    when (format) {
        ArchiveFormat.ZIP -> ZipArchiveInputStream(archive.inputStream().buffered()).use { zip ->
            extractEntries(
                input = zip,
                nextEntry = { zip.nextEntry?.toEntryInfo() },
                counters = counters,
                resolveTarget = resolveTarget,
                relativePath = relativePath,
                maxEntries = maxEntries,
                maxFileBytes = maxFileBytes,
                maxTotalBytes = maxTotalBytes,
            )
        }

        ArchiveFormat.TAR -> TarArchiveInputStream(archive.inputStream().buffered()).use { tar ->
            extractEntries(
                input = tar,
                nextEntry = { tar.nextEntry?.toEntryInfo() },
                counters = counters,
                resolveTarget = resolveTarget,
                relativePath = relativePath,
                maxEntries = maxEntries,
                maxFileBytes = maxFileBytes,
                maxTotalBytes = maxTotalBytes,
            )
        }

        ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(archive.inputStream().buffered()).use { gzip ->
            TarArchiveInputStream(gzip.buffered()).use { tar ->
                extractEntries(
                    input = tar,
                    nextEntry = { tar.nextEntry?.toEntryInfo() },
                    counters = counters,
                    resolveTarget = resolveTarget,
                    relativePath = relativePath,
                    maxEntries = maxEntries,
                    maxFileBytes = maxFileBytes,
                    maxTotalBytes = maxTotalBytes,
                )
            }
        }

        ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(archive.inputStream().buffered()).use { bzip ->
            TarArchiveInputStream(bzip.buffered()).use { tar ->
                extractEntries(
                    input = tar,
                    nextEntry = { tar.nextEntry?.toEntryInfo() },
                    counters = counters,
                    resolveTarget = resolveTarget,
                    relativePath = relativePath,
                    maxEntries = maxEntries,
                    maxFileBytes = maxFileBytes,
                    maxTotalBytes = maxTotalBytes,
                )
            }
        }

        ArchiveFormat.GZIP -> GzipCompressorInputStream(archive.inputStream().buffered()).use { gzip ->
            extractSingleCompressedFile(
                input = gzip,
                entryName = compressedOutputName(filename, format),
                counters = counters,
                resolveTarget = resolveTarget,
                relativePath = relativePath,
                maxEntries = maxEntries,
                maxFileBytes = maxFileBytes,
                maxTotalBytes = maxTotalBytes,
            )
        }

        ArchiveFormat.BZIP2 -> BZip2CompressorInputStream(archive.inputStream().buffered()).use { bzip ->
            extractSingleCompressedFile(
                input = bzip,
                entryName = compressedOutputName(filename, format),
                counters = counters,
                resolveTarget = resolveTarget,
                relativePath = relativePath,
                maxEntries = maxEntries,
                maxFileBytes = maxFileBytes,
                maxTotalBytes = maxTotalBytes,
            )
        }
    }

    ensureLimit()
    return ArchiveExtractionResult(
        entries = counters.entries,
        bytes = counters.bytes,
        files = counters.files,
        format = format.id,
    )
}

internal fun extractZipArchive(
    archive: File,
    resolveTarget: (entryName: String) -> File,
    relativePath: (file: File) -> String,
    ensureLimit: () -> Unit = {},
    maxEntries: Int = MAX_ZIP_ENTRIES,
    maxFileBytes: Long = MAX_ZIP_FILE_BYTES,
    maxTotalBytes: Long = MAX_ZIP_TOTAL_BYTES,
): ArchiveExtractionResult =
    extractArchive(
        archive = archive,
        filename = archive.name,
        resolveTarget = resolveTarget,
        relativePath = relativePath,
        ensureLimit = ensureLimit,
        maxEntries = maxEntries,
        maxFileBytes = maxFileBytes,
        maxTotalBytes = maxTotalBytes,
    )

fun ArchiveExtractionResult.filesJsonArray(): JsonArray =
    JsonArray(files.map { JsonPrimitive(it) })

private fun extractEntries(
    input: InputStream,
    nextEntry: () -> ArchiveEntryInfo?,
    counters: ExtractionCounters,
    resolveTarget: (entryName: String) -> File,
    relativePath: (file: File) -> String,
    maxEntries: Int,
    maxFileBytes: Long,
    maxTotalBytes: Long,
) {
    while (true) {
        val entry = nextEntry() ?: break
        counters.entries += 1
        if (counters.entries > maxEntries) throw IOException("Archive entry count exceeds $maxEntries")

        val entryName = normalizeArchiveEntryName(entry.name)
        val target = resolveTarget(entryName)
        if (entry.isDirectory) {
            target.mkdirs()
        } else if (entry.extractFile) {
            extractFileContent(
                input = input,
                target = target,
                counters = counters,
                relativePath = relativePath,
                maxFileBytes = maxFileBytes,
                maxTotalBytes = maxTotalBytes,
            )
        }
    }
}

private fun extractSingleCompressedFile(
    input: InputStream,
    entryName: String,
    counters: ExtractionCounters,
    resolveTarget: (entryName: String) -> File,
    relativePath: (file: File) -> String,
    maxEntries: Int,
    maxFileBytes: Long,
    maxTotalBytes: Long,
) {
    counters.entries += 1
    if (counters.entries > maxEntries) throw IOException("Archive entry count exceeds $maxEntries")
    extractFileContent(
        input = input,
        target = resolveTarget(entryName),
        counters = counters,
        relativePath = relativePath,
        maxFileBytes = maxFileBytes,
        maxTotalBytes = maxTotalBytes,
    )
}

private fun extractFileContent(
    input: InputStream,
    target: File,
    counters: ExtractionCounters,
    relativePath: (file: File) -> String,
    maxFileBytes: Long,
    maxTotalBytes: Long,
) {
    target.parentFile?.mkdirs()
    var fileBytes = 0L
    target.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            fileBytes += count
            counters.bytes += count
            if (fileBytes > maxFileBytes) {
                throw IOException("Archive single file exceeds ${maxFileBytes / 1024 / 1024} MiB limit")
            }
            if (counters.bytes > maxTotalBytes) {
                throw IOException("Archive extracted total size exceeds ${maxTotalBytes / 1024 / 1024} MiB limit")
            }
            output.write(buffer, 0, count)
        }
    }
    counters.files += relativePath(target)
}

private fun ZipArchiveEntry.toEntryInfo(): ArchiveEntryInfo =
    ArchiveEntryInfo(
        name = name,
        isDirectory = isDirectory,
        extractFile = !isDirectory && !isUnixSymlink,
    )

private fun TarArchiveEntry.toEntryInfo(): ArchiveEntryInfo =
    ArchiveEntryInfo(
        name = name,
        isDirectory = isDirectory,
        extractFile = isFile && !isSymbolicLink && !isLink,
    )

private fun normalizeArchiveEntryName(name: String): String {
    val raw = name.replace('\\', '/').trim()
    if (raw.isBlank() || raw.startsWith("/") || raw.contains("://") || Regex("""^[A-Za-z]:""").containsMatchIn(raw)) {
        throw WorkspaceSecurityException("Archive entry path is invalid")
    }
    val normalized = raw.trimEnd('/')
    if (normalized.isBlank()) {
        throw WorkspaceSecurityException("Archive entry path is invalid")
    }
    if (normalized.split('/').any { it.isBlank() || it == "." || it == ".." }) {
        throw WorkspaceSecurityException("Archive entry contains path traversal")
    }
    return normalized
}

private fun compressedOutputName(filename: String, format: ArchiveFormat): String {
    val source = filename.replace('\\', '/').substringAfterLast('/').trim()
    val lower = source.lowercase(Locale.ROOT)
    val stripped = when {
        format == ArchiveFormat.GZIP && lower.endsWith(".gz") -> source.dropLast(3)
        format == ArchiveFormat.BZIP2 && lower.endsWith(".bz2") -> source.dropLast(4)
        else -> source
    }.ifBlank { "decompressed" }
    return normalizeArchiveEntryName(stripped)
}

private fun detectArchiveFormat(file: File, filename: String? = null, mimeType: String? = null): ArchiveFormat? {
    if (!file.isFile) return null

    val lowerName = (filename ?: file.name).lowercase(Locale.ROOT)
    val normalizedMimeType = mimeType?.lowercase(Locale.ROOT).orEmpty()

    if (lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz")) return ArchiveFormat.TAR_GZ
    if (lowerName.endsWith(".tar.bz2") || lowerName.endsWith(".tbz2") || lowerName.endsWith(".tbz")) return ArchiveFormat.TAR_BZ2
    if (lowerName.endsWith(".zip") || lowerName.endsWith(".jar")) return ArchiveFormat.ZIP
    if (lowerName.endsWith(".tar")) return ArchiveFormat.TAR
    if (lowerName.endsWith(".gz")) return ArchiveFormat.GZIP
    if (lowerName.endsWith(".bz2")) return ArchiveFormat.BZIP2

    if (normalizedMimeType in ZIP_MIME_TYPES) return ArchiveFormat.ZIP
    if (normalizedMimeType in TAR_MIME_TYPES) return ArchiveFormat.TAR
    if (normalizedMimeType in GZIP_MIME_TYPES) return ArchiveFormat.GZIP
    if (normalizedMimeType in BZIP2_MIME_TYPES) return ArchiveFormat.BZIP2

    if (hasZipSignature(file)) return ArchiveFormat.ZIP
    if (hasTarSignature(file)) return ArchiveFormat.TAR
    if (hasGzipSignature(file)) return ArchiveFormat.GZIP
    if (hasBzip2Signature(file)) return ArchiveFormat.BZIP2

    return null
}

private fun hasZipSignature(file: File): Boolean {
    val signature = readSignature(file, 4)
    if (signature.size < 4) return false
    return signature[0] == 'P'.code.toByte() &&
        signature[1] == 'K'.code.toByte() &&
        signature[2] in ZIP_SIGNATURE_THIRD_BYTES &&
        signature[3] in ZIP_SIGNATURE_FOURTH_BYTES_BY_THIRD_BYTE.getValue(signature[2])
}

private fun hasTarSignature(file: File): Boolean {
    val signature = readSignature(file, TAR_SIGNATURE_OFFSET + TAR_SIGNATURE.size)
    if (signature.size < TAR_SIGNATURE_OFFSET + TAR_SIGNATURE.size) return false
    return signature.copyOfRange(TAR_SIGNATURE_OFFSET, TAR_SIGNATURE_OFFSET + TAR_SIGNATURE.size)
        .contentEquals(TAR_SIGNATURE)
}

private fun hasGzipSignature(file: File): Boolean {
    val signature = readSignature(file, 2)
    return signature.size >= 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()
}

private fun hasBzip2Signature(file: File): Boolean {
    val signature = readSignature(file, 3)
    return signature.size >= 3 &&
        signature[0] == 'B'.code.toByte() &&
        signature[1] == 'Z'.code.toByte() &&
        signature[2] == 'h'.code.toByte()
}

private fun readSignature(file: File, size: Int): ByteArray {
    val signature = ByteArray(size)
    var bytesRead = 0
    file.inputStream().use { input ->
        while (bytesRead < signature.size) {
            val count = input.read(signature, bytesRead, signature.size - bytesRead)
            if (count < 0) break
            bytesRead += count
        }
    }
    return signature.copyOf(bytesRead)
}

private data class ArchiveEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val extractFile: Boolean,
)

private data class ExtractionCounters(
    var entries: Int = 0,
    var bytes: Long = 0,
    val files: MutableList<String> = mutableListOf(),
)

private enum class ArchiveFormat(val id: String) {
    ZIP("zip"),
    TAR("tar"),
    TAR_GZ("tar.gz"),
    TAR_BZ2("tar.bz2"),
    GZIP("gz"),
    BZIP2("bz2"),
}

const val MAX_ARCHIVE_ENTRIES = 500
const val MAX_ARCHIVE_FILE_BYTES = 50L * 1024L * 1024L
const val MAX_ARCHIVE_TOTAL_BYTES = 256L * 1024L * 1024L

const val MAX_ZIP_ENTRIES = MAX_ARCHIVE_ENTRIES
const val MAX_ZIP_FILE_BYTES = MAX_ARCHIVE_FILE_BYTES
const val MAX_ZIP_TOTAL_BYTES = MAX_ARCHIVE_TOTAL_BYTES

private val ZIP_SIGNATURE_THIRD_BYTES = setOf(3.toByte(), 5.toByte(), 7.toByte())
private val ZIP_SIGNATURE_FOURTH_BYTES_BY_THIRD_BYTE = mapOf(
    3.toByte() to setOf(4.toByte()),
    5.toByte() to setOf(6.toByte()),
    7.toByte() to setOf(8.toByte()),
)
private val TAR_SIGNATURE = "ustar".toByteArray(Charsets.US_ASCII)
private const val TAR_SIGNATURE_OFFSET = 257

private val ZIP_MIME_TYPES = setOf(
    "application/zip",
    "application/x-zip",
    "application/x-zip-compressed",
    "multipart/x-zip",
    "application/java-archive",
)
private val TAR_MIME_TYPES = setOf("application/x-tar", "application/tar")
private val GZIP_MIME_TYPES = setOf("application/gzip", "application/x-gzip")
private val BZIP2_MIME_TYPES = setOf("application/x-bzip", "application/x-bzip2")
