package cn.edu.bjtu.mis.data.exporting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.IOException
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

object ScheduleExportRenderer {
    private const val PdfWidth = 842
    private const val PdfHeight = 595
    private const val PdfMargin = 36f
    private const val PngWidth = 1600
    private const val PngMargin = 48f
    private const val MaxPngHeight = 24_000

    private val GeneratedAtFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun writePdf(document: ScheduleExportDocument, output: OutputStream) {
        val pdf = PdfDocument()
        val paints = ExportPaints()
        val contentWidth = PdfWidth - PdfMargin * 2
        val contentTop = PdfMargin + headerHeight(document, paints)
        val contentBottom = PdfHeight - PdfMargin - 22f
        val elements = buildElements(document, contentWidth, paints)
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = contentTop

        fun startPage() {
            pageNumber += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PdfWidth, PdfHeight, pageNumber).create())
            drawPageHeader(page!!.canvas, document, pageNumber, PdfWidth.toFloat(), PdfMargin, paints)
            y = contentTop
        }

        fun finishPage() {
            page?.let {
                drawPageFooter(it.canvas, pageNumber, PdfWidth.toFloat(), PdfHeight.toFloat(), PdfMargin, paints)
                pdf.finishPage(it)
            }
            page = null
        }

        startPage()
        elements.forEach { element ->
            if (y + element.height > contentBottom && y > contentTop) {
                finishPage()
                startPage()
            }
            element.draw(page!!.canvas, PdfMargin, y)
            y += element.height
        }
        finishPage()
        pdf.writeTo(output)
        pdf.close()
    }

    fun renderPng(document: ScheduleExportDocument): Bitmap {
        val paints = ExportPaints(scale = 1.65f)
        val margin = PngMargin
        val contentWidth = PngWidth - margin * 2
        val elements = buildElements(document, contentWidth, paints)
        val header = headerHeight(document, paints)
        val height = ceil(margin + header + elements.sumOf { it.height.toDouble() } + margin).toInt()
            .coerceAtLeast(360)
        if (height > MaxPngHeight) {
            throw IOException("PNG 长图过大，无法导出")
        }
        val bitmap = Bitmap.createBitmap(PngWidth, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawPageHeader(canvas, document, pageNumber = null, width = PngWidth.toFloat(), margin = margin, paints = paints)
        var y = margin + header
        elements.forEach { element ->
            element.draw(canvas, margin, y)
            y += element.height
        }
        return bitmap
    }

    private fun buildElements(
        document: ScheduleExportDocument,
        contentWidth: Float,
        paints: ExportPaints,
    ): List<RenderElement> = buildList {
        if (document.blocks.isEmpty()) {
            add(paragraphElement("暂无内容", contentWidth, paints))
        }
        document.blocks.forEachIndexed { index, block ->
            if (index > 0) add(spaceElement(12f * paints.scale))
            block.heading?.takeIf { it.isNotBlank() }?.let {
                add(headingElement(it, contentWidth, paints))
            }
            block.paragraphs.forEach { paragraph ->
                add(paragraphElement(paragraph, contentWidth, paints))
            }
            block.table?.let { table ->
                addAll(tableElements(table, contentWidth, paints))
            }
        }
    }

    private fun headingElement(text: String, width: Float, paints: ExportPaints): RenderElement {
        val lines = wrapText(text, paints.heading, width)
        val lineHeight = paints.heading.lineHeight()
        val height = lines.size * lineHeight + 10f * paints.scale
        return RenderElement(height) { canvas, x, y ->
            drawLines(canvas, lines, x, y + lineHeight, lineHeight, paints.heading)
        }
    }

    private fun paragraphElement(text: String, width: Float, paints: ExportPaints): RenderElement {
        val lines = wrapText(text, paints.body, width)
        val lineHeight = paints.body.lineHeight()
        val height = lines.size * lineHeight + 8f * paints.scale
        return RenderElement(height) { canvas, x, y ->
            drawLines(canvas, lines, x, y + lineHeight, lineHeight, paints.body)
        }
    }

    private fun tableElements(
        table: ScheduleExportTable,
        width: Float,
        paints: ExportPaints,
    ): List<RenderElement> {
        val weights = table.weights()
        val columnWidths = weights.map { width * it / weights.sum() }
        return buildList {
            add(tableRowElement(table.columns, columnWidths, paints, isHeader = true))
            table.rows.forEach { row ->
                val normalized = table.columns.indices.map { index -> row.getOrNull(index).orEmpty() }
                add(tableRowElement(normalized, columnWidths, paints, isHeader = false))
            }
        }
    }

    private fun tableRowElement(
        cells: List<String>,
        columnWidths: List<Float>,
        paints: ExportPaints,
        isHeader: Boolean,
    ): RenderElement {
        val paint = if (isHeader) paints.tableHeader else paints.tableBody
        val paddingX = 6f * paints.scale
        val paddingY = 5f * paints.scale
        val lineHeight = paint.lineHeight()
        val cellLines = cells.mapIndexed { index, cell ->
            wrapText(cell, paint, (columnWidths[index] - paddingX * 2).coerceAtLeast(24f * paints.scale))
        }
        val rowHeight = (cellLines.maxOfOrNull { it.size } ?: 1) * lineHeight + paddingY * 2
        return RenderElement(rowHeight) { canvas, x, y ->
            var left = x
            cells.indices.forEach { index ->
                val cellWidth = columnWidths[index]
                val rect = RectF(left, y, left + cellWidth, y + rowHeight)
                canvas.drawRect(rect, if (isHeader) paints.tableHeaderBackground else paints.tableBackground)
                canvas.drawRect(rect, paints.tableBorder)
                drawLines(
                    canvas = canvas,
                    lines = cellLines[index],
                    x = left + paddingX,
                    firstBaseline = y + paddingY + lineHeight * 0.82f,
                    lineHeight = lineHeight,
                    paint = paint,
                )
                left += cellWidth
            }
        }
    }

    private fun drawPageHeader(
        canvas: Canvas,
        document: ScheduleExportDocument,
        pageNumber: Int?,
        width: Float,
        margin: Float,
        paints: ExportPaints,
    ) {
        val titleBaseline = margin + paints.title.lineHeight() * 0.82f
        canvas.drawText(document.title.take(80), margin, titleBaseline, paints.title)
        val generated = "导出时间 ${document.generatedAt.format(GeneratedAtFormatter)}"
        canvas.drawText(generated, width - margin - paints.meta.measureText(generated), titleBaseline, paints.meta)
        document.subtitle?.takeIf { it.isNotBlank() }?.let {
            canvas.drawText(it.take(120), margin, titleBaseline + paints.subtitle.lineHeight(), paints.subtitle)
        }
        pageNumber?.let {
            val label = "第 $it 页"
            canvas.drawText(label, width - margin - paints.meta.measureText(label), titleBaseline + paints.subtitle.lineHeight(), paints.meta)
        }
        canvas.drawLine(
            margin,
            margin + headerHeight(document, paints) - 8f * paints.scale,
            width - margin,
            margin + headerHeight(document, paints) - 8f * paints.scale,
            paints.divider,
        )
    }

    private fun drawPageFooter(
        canvas: Canvas,
        pageNumber: Int,
        width: Float,
        height: Float,
        margin: Float,
        paints: ExportPaints,
    ) {
        val label = "第 $pageNumber 页"
        canvas.drawText(label, (width - paints.meta.measureText(label)) / 2f, height - margin / 2f, paints.meta)
    }

    private fun headerHeight(document: ScheduleExportDocument, paints: ExportPaints): Float =
        paints.title.lineHeight() + if (document.subtitle.isNullOrBlank()) {
            22f * paints.scale
        } else {
            paints.subtitle.lineHeight() + 22f * paints.scale
        }

    private fun spaceElement(height: Float): RenderElement =
        RenderElement(height) { _, _, _ -> }

    private fun drawLines(
        canvas: Canvas,
        lines: List<String>,
        x: Float,
        firstBaseline: Float,
        lineHeight: Float,
        paint: Paint,
    ) {
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, x, firstBaseline + index * lineHeight, paint)
        }
    }

    private fun wrapText(value: String, paint: Paint, maxWidth: Float): List<String> {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return listOf("")
        return normalized.split('\n').flatMap { line ->
            if (line.isBlank()) {
                listOf("")
            } else {
                wrapSingleLine(line.trim(), paint, maxWidth)
            }
        }
    }

    private fun wrapSingleLine(value: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        value.forEach { char ->
            val candidate = current.toString() + char
            if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                lines += current.toString()
                current.clear()
            }
            current.append(char)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines.ifEmpty { listOf("") }
    }

    private fun ScheduleExportTable.weights(): List<Float> =
        columnWeights.takeIf { it.size == columns.size && it.all { weight -> weight > 0f } }
            ?: List(columns.size) { 1f }

    private fun Paint.lineHeight(): Float {
        val metrics = fontMetrics
        return metrics.descent - metrics.ascent + metrics.leading
    }

    private class ExportPaints(val scale: Float = 1f) {
        val title: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(17, 24, 39)
            textSize = 17f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitle: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 85, 99)
            textSize = 10.5f * scale
        }
        val meta: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(107, 114, 128)
            textSize = 9.5f * scale
        }
        val heading: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(31, 41, 55)
            textSize = 13f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val body: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 65, 81)
            textSize = 10.5f * scale
        }
        val tableHeader: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(17, 24, 39)
            textSize = 9.2f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tableBody: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(31, 41, 55)
            textSize = 8.8f * scale
        }
        val tableHeaderBackground: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(229, 231, 235)
            style = Paint.Style.FILL
        }
        val tableBackground: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val tableBorder: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(209, 213, 219)
            style = Paint.Style.STROKE
            strokeWidth = 0.7f * scale
        }
        val divider: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(209, 213, 219)
            strokeWidth = 0.8f * scale
        }
    }

    private data class RenderElement(
        val height: Float,
        val draw: (Canvas, Float, Float) -> Unit,
    )
}
