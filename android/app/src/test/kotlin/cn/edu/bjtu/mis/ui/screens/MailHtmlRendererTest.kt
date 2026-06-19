package cn.edu.bjtu.mis.ui.screens

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailHtmlRendererTest {
    @Test
    fun mobileModeInjectsViewportAndRelaxesWideTables() {
        val output = mailBodyHtml(
            """
            <table style="width:600px;max-width:600px">
              <tr><td>Hello</td></tr>
            </table>
            """.trimIndent(),
            MailBodyRenderMode.Mobile,
        )

        val document = Jsoup.parse(output)
        val tableStyle = document.selectFirst("table")?.attr("style").orEmpty()

        assertEquals(
            "width=device-width, initial-scale=1, maximum-scale=1",
            document.selectFirst("meta[name=viewport]")?.attr("content"),
        )
        assertFalse(tableStyle.contains("width: 600px"))
        assertFalse(tableStyle.contains("max-width: 600px"))
        assertTrue(tableStyle.contains("width: 100% !important"))
        assertTrue(document.selectFirst("style[data-mail-mobile]") != null)
    }

    @Test
    fun mobileModeRemovesCollapsedContainerStyles() {
        val output = mailBodyHtml(
            """<div style="height:120px;max-height:120px;overflow:hidden">Visible text</div>""",
            MailBodyRenderMode.Mobile,
        )

        val document = Jsoup.parse(output)
        val style = document.selectFirst("div")?.attr("style").orEmpty()

        assertFalse(style.contains("height: 120px"))
        assertFalse(style.contains("max-height: 120px"))
        assertFalse(style.contains("overflow: hidden"))
        assertTrue(style.contains("overflow: visible !important"))
    }

    @Test
    fun mobileModeKeepsImagesResponsive() {
        val output = mailBodyHtml(
            """<img src="https://example.com/image.png" style="width:1200px;height:800px">""",
            MailBodyRenderMode.Mobile,
        )

        val document = Jsoup.parse(output)
        val image = document.selectFirst("img")
        val style = image?.attr("style").orEmpty()

        assertEquals("https://example.com/image.png", image?.attr("src"))
        assertFalse(style.contains("width: 1200px"))
        assertFalse(style.contains("height: 800px"))
        assertTrue(style.contains("max-width: 100% !important"))
        assertTrue(style.contains("height: auto !important"))
    }

    @Test
    fun mobileModeWrapsHtmlFragmentsIntoDocument() {
        val output = mailBodyHtml(
            """<p>Hello <a href="https://example.com">world</a></p>""",
            MailBodyRenderMode.Mobile,
        )

        val document = Jsoup.parse(output)

        assertNotNull(document.selectFirst("html"))
        assertNotNull(document.selectFirst("body"))
        assertEquals("https://example.com", document.selectFirst("a")?.attr("href"))
        assertEquals("Hello world", document.selectFirst("body")?.text())
    }

    @Test
    fun mobileModeFallsBackToPlainTextWhenBodyIsEmpty() {
        val output = mailBodyHtml(
            """<html><body></body></html>""",
            MailBodyRenderMode.Mobile,
        )

        val document = Jsoup.parse(output)

        assertEquals("No message content.", document.selectFirst("pre.mail-plain-text")?.text())
    }

    @Test
    fun originalModeKeepsRawHtmlUnchanged() {
        val rawHtml = """<p><a href="https://example.com">Open link</a></p>"""

        val output = mailBodyHtml(rawHtml, MailBodyRenderMode.Original)

        assertEquals(rawHtml, output)
        assertTrue(output.contains("""href="https://example.com""""))
    }
}
