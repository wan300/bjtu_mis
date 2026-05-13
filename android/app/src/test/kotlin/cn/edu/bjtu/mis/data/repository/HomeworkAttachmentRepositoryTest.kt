package cn.edu.bjtu.mis.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeworkAttachmentRepositoryTest {
    @Test
    fun safeHomeworkAttachmentFileNameReplacesIllegalPathCharacters() {
        assertEquals(
            "week_1_report_.pdf",
            safeHomeworkAttachmentFileName("""week\1/report?.pdf""", "42"),
        )
    }

    @Test
    fun safeHomeworkAttachmentFileNameKeepsNameWithoutExtension() {
        assertEquals("readme", safeHomeworkAttachmentFileName("readme", "42"))
    }

    @Test
    fun safeHomeworkAttachmentFileNameFallsBackForBlankName() {
        assertEquals("homework-attachment-42", safeHomeworkAttachmentFileName("   ", "42"))
    }

    @Test
    fun safeHomeworkAttachmentFileNameCollapsesDuplicateExtension() {
        assertEquals("assignment.pdf", safeHomeworkAttachmentFileName("assignment.pdf.pdf", "42"))
    }

    @Test
    fun homeworkAttachmentPreviewSupportedRejectsArchives() {
        assertTrue(homeworkAttachmentPreviewSupported("paper.pdf"))
        assertTrue(homeworkAttachmentPreviewSupported("answer.docx"))
        assertTrue(homeworkAttachmentPreviewSupported("diagram.png"))
        assertTrue(homeworkAttachmentPreviewSupported("readme"))

        assertFalse(homeworkAttachmentPreviewSupported("archive.zip"))
        assertFalse(homeworkAttachmentPreviewSupported("archive.RAR"))
        assertFalse(homeworkAttachmentPreviewSupported("archive.7z"))
    }
}
