package cn.edu.bjtu.mis.data.agent.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedFileExportsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun listsOnlyOutputFilesAndResultsZip() {
        val root = temp.newFolder("workspace")
        write(File(root, "output/report.pdf"), "pdf")
        write(File(root, "output/nested/notes.md"), "# notes")
        write(File(root, "work/draft.txt"), "draft")
        write(File(root, "inbox/source.docx"), "source")
        write(File(root, "results.zip"), "zip")

        val files = listGeneratedFilesInWorkspace(root)

        assertEquals(
            listOf("output/report.pdf", "output/nested/notes.md", "results.zip"),
            files.map { it.relativePath },
        )
        assertEquals(listOf("output", "output", "package"), files.map { it.role })
    }

    @Test
    fun guessesCommonGeneratedFileMimeTypes() {
        assertEquals("application/pdf", guessAgentGeneratedFileMimeType("report.pdf"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            guessAgentGeneratedFileMimeType("report.docx"),
        )
        assertEquals("application/zip", guessAgentGeneratedFileMimeType("results.zip"))
        assertEquals("text/markdown", guessAgentGeneratedFileMimeType("summary.md"))
        assertEquals("text/plain", guessAgentGeneratedFileMimeType("notes.txt"))
    }

    @Test
    fun identifiesPreviewableGeneratedFileTypes() {
        assertEquals("pdf", agentGeneratedFilePreviewKind("output/report.pdf", "application/pdf"))
        assertEquals(
            "docx",
            agentGeneratedFilePreviewKind(
                "output/report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
        assertEquals("markdown", agentGeneratedFilePreviewKind("output/final.md", "text/markdown"))
        assertEquals("text", agentGeneratedFilePreviewKind("output/data.json", "application/json"))
        assertNull(agentGeneratedFilePreviewKind("results.zip", "application/zip"))
    }

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
