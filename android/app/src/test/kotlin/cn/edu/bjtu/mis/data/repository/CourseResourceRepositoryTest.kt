package cn.edu.bjtu.mis.data.repository

import cn.edu.bjtu.mis.data.provider.buildCourseResourceOnlinePreviewUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class CourseResourceRepositoryTest {
    @Test
    fun courseResourcePreviewSupportedRejectsArchivesAndExecutables() {
        assertTrue(courseResourcePreviewSupported("slides.pptx"))
        assertTrue(courseResourcePreviewSupported("handout.docx"))
        assertTrue(courseResourcePreviewSupported("paper.pdf"))

        assertFalse(courseResourcePreviewSupported("archive.zip"))
        assertFalse(courseResourcePreviewSupported("archive.rar"))
        assertFalse(courseResourcePreviewSupported("archive.7z"))
        assertFalse(courseResourcePreviewSupported("installer.exe"))
        assertFalse(courseResourcePreviewSupported("mobile.apk"))
    }

    @Test
    fun courseResourceOnlinePreviewUrlEncodesKkFileUrl() {
        val previewUrl = buildCourseResourceOnlinePreviewUrl("/rp/2026/03/17/swf/demo.pdf")
        val encoded = URI(previewUrl).rawQuery.substringAfter("url=")
        val decodedBase64 = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        val fileUrl = String(Base64.getDecoder().decode(decodedBase64), StandardCharsets.UTF_8)

        assertEquals("http://123.121.147.7:1936/kk/rp/2026/03/17/swf/demo.pdf", fileUrl)
    }
}
