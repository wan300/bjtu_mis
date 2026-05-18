package cn.edu.bjtu.mis.data.agent.tools

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipWorkspaceExtractorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun extractsNestedUnicodeFiles() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "homework.zip")
        writeZip(
            archive,
            mapOf(
                "\u8bf4\u660e.txt" to "\u9644\u4ef6\u8bf4\u660e",
                "nested/task.md" to "# \u4f5c\u4e1a\u8981\u6c42",
            ),
        )
        val targetDir = File(root, "work/attachments/homework")

        val result = extractZipArchive(
            archive = archive,
            resolveTarget = { entryName -> File(targetDir, entryName) },
            relativePath = { file -> file.relativeTo(root).path.replace(File.separatorChar, '/') },
        )

        assertEquals("zip", result.format)
        assertEquals(2, result.entries)
        assertEquals(
            listOf(
                "work/attachments/homework/\u8bf4\u660e.txt",
                "work/attachments/homework/nested/task.md",
            ),
            result.files,
        )
        assertEquals("\u9644\u4ef6\u8bf4\u660e", File(targetDir, "\u8bf4\u660e.txt").readText())
        assertEquals("# \u4f5c\u4e1a\u8981\u6c42", File(targetDir, "nested/task.md").readText())
    }

    @Test
    fun detectsZipArchiveWithoutZipExtension() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "makefile_example")
        writeZip(archive, mapOf("Makefile" to "all:\n\tcc main.c -o main\n"))

        assertTrue(isZipArchive(archive))
        assertTrue(isSupportedArchive(archive))
    }

    @Test
    fun extractsJarAsZipArchive() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "library.jar")
        JarOutputStream(archive.outputStream().buffered()).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/plugin.txt"))
            jar.write("plugin".toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }

        val result = extractArchiveToDirectory(root, archive)

        assertEquals("zip", result.format)
        assertEquals("plugin", File(root, "work/attachments/library/META-INF/plugin.txt").readText())
    }

    @Test
    fun extractsTarArchive() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "homework.tar")
        writeTar(archive, mapOf("a.txt" to "alpha", "nested/b.txt" to "beta"))

        val result = extractArchiveToDirectory(root, archive)

        assertEquals("tar", result.format)
        assertEquals(2, result.entries)
        assertEquals("alpha", File(root, "work/attachments/homework/a.txt").readText())
        assertEquals("beta", File(root, "work/attachments/homework/nested/b.txt").readText())
    }

    @Test
    fun extractsTarGzipArchive() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "homework.tar.gz")
        GZIPOutputStream(archive.outputStream().buffered()).use { gzip ->
            writeTar(gzip, mapOf("report.md" to "# Report", "src/main.kt" to "fun main() {}"))
        }

        val result = extractArchiveToDirectory(root, archive)

        assertEquals("tar.gz", result.format)
        assertEquals("# Report", File(root, "work/attachments/homework/report.md").readText())
        assertEquals("fun main() {}", File(root, "work/attachments/homework/src/main.kt").readText())
    }

    @Test
    fun extractsTarBzip2Archive() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "homework.tar.bz2")
        BZip2CompressorOutputStream(archive.outputStream().buffered()).use { bzip ->
            writeTar(bzip, mapOf("notes.txt" to "notes"))
        }

        val result = extractArchiveToDirectory(root, archive)

        assertEquals("tar.bz2", result.format)
        assertEquals("notes", File(root, "work/attachments/homework/notes.txt").readText())
    }

    @Test
    fun extractsGzipSingleFile() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "notes.txt.gz")
        GZIPOutputStream(archive.outputStream().buffered()).use { gzip ->
            gzip.write("plain text".toByteArray(Charsets.UTF_8))
        }

        val result = extractArchiveToDirectory(root, archive)

        assertEquals("gz", result.format)
        assertEquals(listOf("work/attachments/notes.txt/notes.txt"), result.files)
        assertEquals("plain text", File(root, "work/attachments/notes.txt/notes.txt").readText())
    }

    @Test
    fun extractsBzip2SingleFile() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "notes.txt.bz2")
        BZip2CompressorOutputStream(archive.outputStream().buffered()).use { bzip ->
            bzip.write("plain text".toByteArray(Charsets.UTF_8))
        }

        val result = extractArchiveToDirectory(root, archive)

        assertEquals("bz2", result.format)
        assertEquals(listOf("work/attachments/notes.txt/notes.txt"), result.files)
        assertEquals("plain text", File(root, "work/attachments/notes.txt/notes.txt").readText())
    }

    @Test
    fun rejectsUnsupportedArchive() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "plain.txt").apply { writeText("not an archive") }

        assertFalse(isSupportedArchive(archive))
        val error = assertThrows(UnsupportedArchiveException::class.java) {
            extractArchiveToDirectory(root, archive)
        }

        assertTrue(error.message.orEmpty().contains("Unsupported archive format"))
    }

    @Test
    fun rejectsZipEntryPathTraversal() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "escape.zip")
        writeZip(archive, mapOf("../escape.txt" to "bad"))

        assertThrows(WorkspaceSecurityException::class.java) {
            extractZipArchive(
                archive = archive,
                resolveTarget = { entryName -> File(root, "work/$entryName") },
                relativePath = { file -> file.relativeTo(root).path.replace(File.separatorChar, '/') },
            )
        }
    }

    @Test
    fun rejectsTooManyZipEntries() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "too-many.zip")
        writeZip(archive, mapOf("a.txt" to "a", "b.txt" to "b"))

        val error = assertThrows(IOException::class.java) {
            extractZipArchive(
                archive = archive,
                resolveTarget = { entryName -> File(root, "work/$entryName") },
                relativePath = { file -> file.relativeTo(root).path.replace(File.separatorChar, '/') },
                maxEntries = 1,
            )
        }

        assertTrue(error.message.orEmpty().contains("Archive entry count exceeds 1"))
    }

    @Test
    fun rejectsOversizedZipEntry() {
        val root = temp.newFolder("workspace")
        val archive = File(root, "oversized.zip")
        writeZip(archive, mapOf("large.txt" to "12345"))

        val error = assertThrows(IOException::class.java) {
            extractZipArchive(
                archive = archive,
                resolveTarget = { entryName -> File(root, "work/$entryName") },
                relativePath = { file -> file.relativeTo(root).path.replace(File.separatorChar, '/') },
                maxFileBytes = 4,
            )
        }

        assertTrue(error.message.orEmpty().contains("Archive single file exceeds"))
    }

    private fun extractArchiveToDirectory(root: File, archive: File): ArchiveExtractionResult {
        val targetDir = File(root, "work/attachments/${archiveTargetName(archive.name)}")
        return extractArchive(
            archive = archive,
            resolveTarget = { entryName -> File(targetDir, entryName) },
            relativePath = { file -> file.relativeTo(root).path.replace(File.separatorChar, '/') },
        )
    }

    private fun archiveTargetName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tar.gz") -> name.dropLast(7)
            lower.endsWith(".tgz") -> name.dropLast(4)
            lower.endsWith(".tar.bz2") -> name.dropLast(8)
            lower.endsWith(".tbz2") -> name.dropLast(5)
            lower.endsWith(".tbz") -> name.dropLast(4)
            lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".tar") ||
                lower.endsWith(".gz") || lower.endsWith(".bz2") -> name.substringBeforeLast('.')
            else -> name.substringBeforeLast('.', name)
        }.ifBlank { "archive" }
    }

    private fun writeZip(target: File, entries: Map<String, String>) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun writeTar(target: File, entries: Map<String, String>) {
        target.outputStream().buffered().use { output ->
            writeTar(output, entries)
        }
    }

    private fun writeTar(output: java.io.OutputStream, entries: Map<String, String>) {
        TarArchiveOutputStream(output).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val entry = TarArchiveEntry(name).apply {
                    size = bytes.size.toLong()
                }
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }
}
