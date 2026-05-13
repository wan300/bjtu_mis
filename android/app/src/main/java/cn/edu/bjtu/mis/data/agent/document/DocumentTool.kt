package cn.edu.bjtu.mis.data.agent.document

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import cn.edu.bjtu.mis.data.agent.tools.AgentTool
import cn.edu.bjtu.mis.data.agent.tools.ToolArtifact
import cn.edu.bjtu.mis.data.agent.tools.ToolResult
import cn.edu.bjtu.mis.data.agent.tools.WorkspaceManager
import cn.edu.bjtu.mis.data.agent.tools.errorOutput
import cn.edu.bjtu.mis.data.agent.tools.objectSchema
import cn.edu.bjtu.mis.data.agent.tools.requiredString
import cn.edu.bjtu.mis.data.agent.tools.string
import cn.edu.bjtu.mis.data.agent.tools.stringSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DocumentTool(
    context: Context,
    private val workspaceManager: WorkspaceManager,
) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun tools(): List<AgentTool> = listOf(
        ExtractPdfTool(),
        ExtractDocxTool(),
        GeneratePdfTool(),
        GenerateDocxTool(),
    )

    private inner class ExtractPdfTool : AgentTool {
        override val name = "document.extract_pdf"
        override val description = "Extract basic text from a PDF into Markdown-like text."
        override val parameters = objectSchema(
            "path" to stringSchema("Relative PDF path."),
            "outputPath" to stringSchema("Relative output path under work/."),
            required = listOf("path", "outputPath"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val input = workspaceManager.resolveRead(taskId, arguments.requiredString("path"))
            if (!input.isFile) return@withContext ToolResult(errorOutput("not_found", "PDF 不存在"))
            val output = workspaceManager.resolveWrite(taskId, arguments.requiredString("outputPath"))
            output.parentFile?.mkdirs()
            val text = PDDocument.load(input).use { PDFTextStripper().getText(it).trim() }
            val content = if (text.isBlank()) {
                "_warning: 未提取到文本。该 PDF 可能是扫描图片，v1 不做本地 OCR。_\n"
            } else {
                text
            }
            output.writeText(content)
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("output_path", workspaceManager.relativePath(taskId, output))
                    put("warning", text.isBlank())
                },
                artifacts = listOf(ToolArtifact(workspaceManager.relativePath(taskId, output), "text/markdown", "intermediate", output.length())),
            )
        }
    }

    private inner class ExtractDocxTool : AgentTool {
        override val name = "document.extract_docx"
        override val description = "Extract basic text from a DOCX into Markdown-like text."
        override val parameters = objectSchema(
            "path" to stringSchema("Relative DOCX path."),
            "outputPath" to stringSchema("Relative output path under work/."),
            required = listOf("path", "outputPath"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val input = workspaceManager.resolveRead(taskId, arguments.requiredString("path"))
            if (!input.isFile) return@withContext ToolResult(errorOutput("not_found", "DOCX 不存在"))
            val output = workspaceManager.resolveWrite(taskId, arguments.requiredString("outputPath"))
            output.parentFile?.mkdirs()
            val text = extractDocxText(input = input.absolutePath)
            output.writeText(text.ifBlank { "_warning: 未提取到 DOCX 文本。_\n" })
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("output_path", workspaceManager.relativePath(taskId, output))
                    put("warning", text.isBlank())
                },
                artifacts = listOf(ToolArtifact(workspaceManager.relativePath(taskId, output), "text/markdown", "intermediate", output.length())),
            )
        }
    }

    private inner class GeneratePdfTool : AgentTool {
        override val name = "document.generate_pdf"
        override val description = "Generate a simple PDF from Markdown-like text."
        override val parameters = objectSchema(
            "title" to stringSchema("Document title."),
            "contentMarkdown" to stringSchema("Markdown-like content."),
            "outputPath" to stringSchema("Relative output path under output/."),
            required = listOf("title", "contentMarkdown", "outputPath"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val output = workspaceManager.resolveWrite(taskId, arguments.requiredString("outputPath"))
            output.parentFile?.mkdirs()
            generatePdf(arguments.string("title").orEmpty(), arguments.requiredString("contentMarkdown"), output.absolutePath)
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("path", workspaceManager.relativePath(taskId, output))
                    put("size_bytes", output.length())
                    put("warning", "复杂 Markdown 会降级为纯文本排版")
                },
                artifacts = listOf(ToolArtifact(workspaceManager.relativePath(taskId, output), "application/pdf", "output", output.length())),
            )
        }
    }

    private inner class GenerateDocxTool : AgentTool {
        override val name = "document.generate_docx"
        override val description = "Generate a simple DOCX from Markdown-like text."
        override val parameters = objectSchema(
            "title" to stringSchema("Document title."),
            "contentMarkdown" to stringSchema("Markdown-like content."),
            "outputPath" to stringSchema("Relative output path under output/."),
            required = listOf("title", "contentMarkdown", "outputPath"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val output = workspaceManager.resolveWrite(taskId, arguments.requiredString("outputPath"))
            output.parentFile?.mkdirs()
            generateDocx(arguments.string("title").orEmpty(), arguments.requiredString("contentMarkdown"), output.absolutePath)
            ToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("path", workspaceManager.relativePath(taskId, output))
                    put("size_bytes", output.length())
                    put("warning", "复杂 Markdown 会降级为基础段落")
                },
                artifacts = listOf(ToolArtifact(workspaceManager.relativePath(taskId, output), DOCX_MIME, "output", output.length())),
            )
        }
    }

    private fun extractDocxText(input: String): String {
        ZipFile(input).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return ""
            val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            val paragraphs = mutableListOf<String>()
            val current = StringBuilder()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "w:p" && current.isNotEmpty()) {
                            paragraphs += current.toString()
                            current.clear()
                        }
                    }
                    XmlPullParser.TEXT -> current.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "w:p" && current.isNotEmpty()) {
                            paragraphs += current.toString()
                            current.clear()
                        }
                    }
                }
                parser.next()
            }
            if (current.isNotEmpty()) paragraphs += current.toString()
            return paragraphs.joinToString("\n\n") { it.trim() }.trim()
        }
    }

    private fun generatePdf(title: String, markdown: String, outputPath: String) {
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; isFakeBoldText = true }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var y = margin
        fun newPage() {
            pdf.finishPage(page)
            pageNumber += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = margin
        }
        if (title.isNotBlank()) {
            page.canvas.drawText(title.take(80), margin, y, titlePaint)
            y += 32f
        }
        markdown.lines().flatMap { wrapLine(it, 86) }.forEach { line ->
            if (y > pageHeight - margin) newPage()
            page.canvas.drawText(line, margin, y, bodyPaint)
            y += 18f
        }
        pdf.finishPage(page)
        java.io.File(outputPath).outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    private fun generateDocx(title: String, markdown: String, outputPath: String) {
        val paragraphs = listOf(title).filter { it.isNotBlank() } + markdown.lines()
        ZipOutputStream(java.io.File(outputPath).outputStream().buffered()).use { zip ->
            zip.putText("[Content_Types].xml", CONTENT_TYPES)
            zip.putText("_rels/.rels", ROOT_RELS)
            zip.putText("word/_rels/document.xml.rels", WORD_RELS)
            zip.putText(
                "word/document.xml",
                DOCUMENT_PREFIX + paragraphs.joinToString("") { paragraphXml(it) } + DOCUMENT_SUFFIX,
            )
        }
    }

    private fun ZipOutputStream.putText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun paragraphXml(value: String): String =
        "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(value)}</w:t></w:r></w:p>"

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun wrapLine(value: String, width: Int): List<String> {
        if (value.isBlank()) return listOf("")
        val clean = value.replace("\t", "    ")
        return clean.chunked(width)
    }

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        const val WORD_RELS = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
        const val DOCUMENT_PREFIX = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>"""
        const val DOCUMENT_SUFFIX = """<w:sectPr/></w:body></w:document>"""
    }
}
