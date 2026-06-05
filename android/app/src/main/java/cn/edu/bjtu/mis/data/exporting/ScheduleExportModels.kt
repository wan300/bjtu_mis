package cn.edu.bjtu.mis.data.exporting

import java.time.LocalDateTime

enum class ScheduleExportFormat(val label: String, val extension: String, val mimeType: String) {
    Pdf("PDF", "pdf", "application/pdf"),
    Png("PNG", "png", "image/png"),
}

enum class CalendarExportScope(val label: String) {
    TermOverview("完整学期周表"),
    Month("当前月视图"),
    Week("当前周视图"),
}

data class ScheduleExportDocument(
    val title: String,
    val subtitle: String? = null,
    val generatedAt: LocalDateTime,
    val blocks: List<ScheduleExportBlock>,
) {
    fun flattenedText(): String =
        buildString {
            appendLine(title)
            subtitle?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            blocks.forEach { block ->
                block.heading?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                block.paragraphs.forEach(::appendLine)
                block.table?.let { table ->
                    appendLine(table.columns.joinToString(" "))
                    table.rows.forEach { row -> appendLine(row.joinToString(" ")) }
                }
            }
        }
}

data class ScheduleExportBlock(
    val heading: String? = null,
    val paragraphs: List<String> = emptyList(),
    val table: ScheduleExportTable? = null,
)

data class ScheduleExportTable(
    val columns: List<String>,
    val rows: List<List<String>>,
    val columnWeights: List<Float> = emptyList(),
)

data class CalendarExportData(
    val bucketsByDate: Map<java.time.LocalDate, cn.edu.bjtu.mis.data.calendar.TaskCalendarBuckets>,
    val todosByDate: Map<java.time.LocalDate, List<cn.edu.bjtu.mis.model.UserTodoItem>>,
    val employmentEventsByDate: Map<java.time.LocalDate, List<cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent>>,
)
