package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.model.AcademicProgressCourse
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.CourseSelectionCourse
import cn.edu.bjtu.mis.model.CourseSelectionData
import cn.edu.bjtu.mis.model.CreditBucket
import cn.edu.bjtu.mis.model.CreditSummary
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmptyRoomRow
import cn.edu.bjtu.mis.model.EmptyRoomSlotHeader
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.ProfileField
import cn.edu.bjtu.mis.model.ProfileSection
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreDetailData
import cn.edu.bjtu.mis.model.ScoreDetailField
import cn.edu.bjtu.mis.model.ScoreDetailTable
import cn.edu.bjtu.mis.model.ScoreItem
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.model.TimetableData
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

fun parseSelectOptions(
    document: Element,
    fieldName: String,
    includeBlank: Boolean = false,
): Pair<List<TermOption>, String?> {
    val select = document.selectFirst("select[name=$fieldName], select#$fieldName")
        ?: return emptyList<TermOption>() to null
    val options = select.select("option").mapNotNull { option ->
        val value = normalizeSpace(option.attr("value"))
        if (value.isBlank() && !includeBlank) return@mapNotNull null
        TermOption(value = value, label = normalizeSpace(option.text()).ifBlank { value }, selected = option.hasAttr("selected"))
    }
    return options to (options.firstOrNull { it.selected }?.value ?: options.firstOrNull()?.value)
}

fun parseInputValue(document: Element, fieldName: String): String? =
    document.selectFirst("[name=$fieldName]")?.attr("value")?.let(::normalizeSpace)

data class CourseSelectionAction(
    val actionUrl: String,
    val method: String,
    val fields: Map<String, String>,
)

data class ParsedCourseSelectionPage(
    val data: CourseSelectionData,
    val actions: Map<String, CourseSelectionAction>,
    val dropActions: Map<String, CourseSelectionAction>,
)

fun parseCourseSelectionPage(
    html: String,
    pageUrl: String = "https://aa.bjtu.edu.cn/course_selection/courseselecttask/selects/",
): ParsedCourseSelectionPage {
    val document = Jsoup.parse(html, pageUrl)
    val selectedCourses = mutableListOf<CourseSelectionCourse>()
    val availableCourses = mutableListOf<CourseSelectionCourse>()
    val actions = mutableMapOf<String, CourseSelectionAction>()
    val dropActions = mutableMapOf<String, CourseSelectionAction>()
    val submit = submitAction(document, pageUrl)
    var canSubmit = submit.actionUrl != null
    var submitError = submit.error

    val selectedTable = document.selectFirst("#selected-container table")
    directRows(selectedTable).drop(1).forEachIndexed { index, cells ->
        courseSelectionCourse(cells, selected = true, index = index)?.let { course ->
            selectedCourses += course
            dropAction(cells.firstOrNull(), pageUrl, submit.fields)?.let { action ->
                dropActions[course.key] = action
            }
        }
    }

    val candidateTables = document.select("table.table-bordered").ifEmpty { document.select("table") }
    val tables = candidateTables.filter { table -> table.parents().none { it.id() == "selected-container" } }
    val availableTable = tables.getOrNull(1) ?: tables.getOrNull(0)

    directRows(availableTable).drop(1).forEachIndexed { index, cells ->
        val course = courseSelectionCourse(cells, selected = false, index = index) ?: return@forEachIndexed
        availableCourses += course
        val (checkboxName, checkboxValue) = checkboxPayload(cells.firstOrNull())
        if (submit.actionUrl != null && checkboxName != null) {
            actions[course.key] = CourseSelectionAction(
                actionUrl = submit.actionUrl,
                method = submit.method,
                fields = submit.fields + (checkboxName to (checkboxValue ?: "on")),
            )
        } else if (checkboxName == null) {
            canSubmit = false
            submitError = submitError ?: "无法解析选课提交入口：目标课程行没有 checkbox name。"
        }
    }

    return ParsedCourseSelectionPage(
        data = CourseSelectionData(
            selectedCourses = selectedCourses,
            availableCourses = availableCourses,
            canSubmit = canSubmit,
            submitError = submitError,
        ),
        actions = actions,
        dropActions = dropActions,
    )
}

data class CourseSelectionCaptchaForm(
    val imageUrl: String?,
    val inputName: String?,
    val fields: Map<String, String>,
    val prompt: String?,
)

fun parseCourseSelectionCaptcha(html: String, pageUrl: String): CourseSelectionCaptchaForm {
    val document = Jsoup.parse(html, pageUrl)
    val modal = document.select(".modal, .bootbox").firstOrNull { it.selectFirst("img") != null } ?: document
    val image = modal.selectFirst("img")
    val form = image?.parents()?.firstOrNull { it.tagName().equals("form", ignoreCase = true) }
        ?: document.selectFirst("form")
    val action = form?.attr("action")?.takeIf { it.isNotBlank() }?.let { resolveUrl(pageUrl, it) } ?: pageUrl
    val inputName = (form?.select("input") ?: document.select("input"))
        .firstOrNull { input ->
            val type = input.attr("type").ifBlank { "text" }.lowercase()
            val name = normalizeSpace(input.attr("name"))
            name.isNotBlank() && type !in setOf("hidden", "submit", "button", "checkbox", "radio")
        }
        ?.attr("name")
        ?.let(::normalizeSpace)
    return CourseSelectionCaptchaForm(
        imageUrl = image?.attr("src")?.takeIf { it.isNotBlank() }?.let { resolveUrl(pageUrl, it) },
        inputName = inputName,
        fields = formFields(form) + ("__action__" to action),
        prompt = normalizeSpace(modal.text()).takeIf { it.isNotBlank() },
    )
}

private data class SubmitAction(
    val actionUrl: String?,
    val method: String,
    val fields: Map<String, String>,
    val error: String?,
)

private fun submitAction(document: Element, pageUrl: String): SubmitAction {
    val submit = document.selectFirst("a.btn-primary, button.btn-primary, input[type=submit]")
    val form = submit?.parents()?.firstOrNull { it.tagName().equals("form", ignoreCase = true) }
        ?: document.selectFirst("form")
    val method = form?.attr("method")?.let(::normalizeSpace)?.lowercase()?.ifBlank { null } ?: "post"
    val candidates = mutableListOf<String>()
    if (submit != null) {
        listOf("data-url", "data-href", "data-action", "formaction", "href").forEach { attr ->
            val value = normalizeSpace(submit.attr(attr))
            if (value.isNotBlank() && value != "#" && !value.startsWith("javascript:", ignoreCase = true)) {
                candidates += value
            }
        }
    }
    if (form != null) {
        candidates += form.attr("action").takeIf { it.isNotBlank() } ?: pageUrl
    }
    val actionUrl = candidates.firstOrNull()?.let { resolveUrl(pageUrl, it) }
    return SubmitAction(
        actionUrl = actionUrl,
        method = method,
        fields = formFields(form),
        error = if (actionUrl == null) "无法解析选课提交入口：页面没有暴露 form/action/data-url。" else null,
    )
}

private fun formFields(form: Element?): Map<String, String> {
    if (form == null) return emptyMap()
    return form.select("input[name]").mapNotNull { input ->
        val name = normalizeSpace(input.attr("name"))
        val type = input.attr("type").lowercase()
        if (name.isBlank() || type in setOf("checkbox", "radio", "submit", "button", "image", "file")) {
            null
        } else {
            name to input.attr("value")
        }
    }.toMap()
}

private fun checkboxPayload(cell: Element?): Pair<String?, String?> {
    val checkbox = cell?.selectFirst("input[type=checkbox]") ?: return null to null
    val name = normalizeSpace(checkbox.attr("name"))
    if (name.isBlank()) return null to null
    return name to checkbox.attr("value").ifBlank { "on" }
}

private fun onclickActionCandidate(value: String): String? =
    Regex("""["']([^"']*(?:delete|courseselecttask)[^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::normalizeSpace)
        ?.takeIf { it.isNotBlank() && !it.startsWith("javascript:", ignoreCase = true) }

private fun dropAction(cell: Element?, pageUrl: String, baseFields: Map<String, String>): CourseSelectionAction? {
    val trigger = cell?.selectFirst(".select-delete-btn, [data-pk], [data-url], [data-href], [data-action], [formaction], a, button, input")
        ?: return null
    val dataPk = listOf("data-pk", "pk", "value")
        .firstNotNullOfOrNull { attr -> normalizeSpace(trigger.attr(attr)).takeIf { it.isNotBlank() } }
    val candidates = mutableListOf<String>()
    listOf("href", "data-url", "data-href", "data-action", "formaction").forEach { attr ->
        val value = normalizeSpace(trigger.attr(attr))
        if (value.isNotBlank() && value != "#" && !value.startsWith("javascript:", ignoreCase = true)) {
            candidates += value
        }
    }
    onclickActionCandidate(normalizeSpace(trigger.attr("onclick")))?.let(candidates::add)
    if (candidates.isEmpty() && dataPk != null) {
        candidates += "/course_selection/courseselecttask/delete/"
    }
    val actionUrl = candidates.firstOrNull()?.let { resolveUrl(pageUrl, it) } ?: return null
    val method = listOf("data-method", "method")
        .firstNotNullOfOrNull { attr -> normalizeSpace(trigger.attr(attr)).lowercase().takeIf { it.isNotBlank() } }
        ?: "post"
    val fields = if (dataPk == null) baseFields else baseFields + ("pk" to dataPk)
    return CourseSelectionAction(actionUrl = actionUrl, method = method, fields = fields)
}

private fun courseSelectionCourse(cells: List<Element>, selected: Boolean, index: Int): CourseSelectionCourse? {
    val texts = cells.map { normalizeSpace(it.text()) }
    if (texts.size < 2 || texts[1].isBlank()) return null
    val (key, code, section) = courseSelectionKey(texts[1])
    val status = texts[0].ifBlank { if (selected) "selected" else "available" }
    val remainingText = texts.getOrNull(2)?.takeIf { it.isNotBlank() }
    return CourseSelectionCourse(
        key = key.ifBlank { "course_$index" },
        status = status,
        selected = selected || status.contains("已选") || status.contains("selected", ignoreCase = true),
        courseName = texts[1],
        courseCode = code,
        section = section,
        remaining = remainingText?.let { Regex("""-?\d+""").find(it)?.value?.toIntOrNull() },
        remainingText = remainingText,
        credit = texts.getOrNull(3)?.takeIf { it.isNotBlank() },
        courseType = texts.getOrNull(4)?.takeIf { it.isNotBlank() },
        examType = texts.getOrNull(5)?.takeIf { it.isNotBlank() },
        teacher = texts.getOrNull(6)?.takeIf { it.isNotBlank() },
        timeLocation = texts.getOrNull(7)?.takeIf { it.isNotBlank() },
        note = texts.getOrNull(8)?.takeIf { it.isNotBlank() },
    )
}

private fun courseSelectionKey(courseName: String): Triple<String, String?, String?> {
    val text = normalizeSpace(courseName)
    val code = Regex("""^([A-Za-z]\d+[A-Za-z]?)""").find(text)?.groupValues?.get(1)
    val section = Regex("""\s(\d{2})(?:\s|$)""").find(text)?.groupValues?.get(1)
    val key = when {
        code != null && section != null -> "${code}_$section"
        code != null -> code
        else -> text
    }
    return Triple(key, code, section)
}

private fun directRows(table: Element?): List<List<Element>> {
    if (table == null) return emptyList()
    return table.select("tr").mapNotNull { row ->
        row.select("> th, > td").takeIf { it.isNotEmpty() }
    }
}

private fun resolveUrl(baseUrl: String, value: String): String =
    URI(baseUrl).resolve(value.trim()).toString()

fun parseTimetable(html: String): TimetableData {
    val document = Jsoup.parse(html)
    val table = document.selectFirst("table.table, table") ?: return TimetableData()
    val rows = table.select("> tbody > tr, > tr")
    if (rows.size < 2) return TimetableData()
    val days = rows.first().select("> th, > td").drop(1).map { normalizeSpace(it.text()) }
    val periods = linkedSetOf<String>()
    val entries = mutableListOf<CourseEntry>()

    for (row in rows.drop(1)) {
        val cells = row.select("> th, > td")
        if (cells.size < 2) continue
        val periodText = normalizeSpace(cells[0].text())
        val periodLabel = Regex("""\d+""").find(periodText)?.let { "Period ${it.value}" } ?: periodText
        val timeRange = Regex("""\[(\d{2}:\d{2}-\d{2}:\d{2})]""").find(periodText)?.groupValues?.get(1)
        if (periodLabel.isNotBlank()) periods.add(periodLabel)
        cells.drop(1).forEachIndexed { dayIndex, cell ->
            val weekday = days.getOrNull(dayIndex) ?: "Day ${dayIndex + 1}"
            val blocks = cell.select("> div").filter { normalizeSpace(it.text()).isNotBlank() }
                .ifEmpty { if (normalizeSpace(cell.text()).isBlank()) emptyList() else listOf(cell) }
            for (block in blocks) {
                val blockText = normalizeSpace(block.text())
                val code = Regex("""([A-Z]\d+[A-Z]?)\s*\[([^]]+)]""").find(blockText) ?: continue
                val nameNode = block.select("span").firstOrNull { !it.classNames().contains("text-muted") }
                val courseName = normalizeSpace(nameNode?.text()).ifBlank { blockText.substringAfter("]").trim() }
                val metaNode = block.select("div").firstOrNull { it.attr("style").contains("max-width") }
                val teacher = metaNode?.selectFirst("i")?.text()?.let(::normalizeSpace)
                val weeks = normalizeSpace(metaNode?.text()?.replace(teacher.orEmpty(), "")).ifBlank { null }
                val locationText = normalizeSpace(block.selectFirst("span.text-muted")?.text())
                val (campus, building, roomName) = splitLocation(locationText)
                entries += CourseEntry(
                    weekday = weekday,
                    period = periodLabel,
                    timeRange = timeRange,
                    courseCode = code.groupValues[1],
                    section = code.groupValues[2],
                    courseName = courseName,
                    teacher = teacher,
                    weeks = weeks,
                    campus = campus,
                    building = building,
                    room = roomName,
                    locationText = locationText.ifBlank { null },
                )
            }
        }
    }
    return TimetableData(days = days, periods = periods.toList(), entries = entries)
}

fun parseExams(html: String, requestedTerm: String? = null): ExamData {
    val document = Jsoup.parse(html)
    val (options, currentTerm) = parseSelectOptions(document, "zxjxjhh")
    val rows = document.selectFirst("table.table, table")?.let(::tableRows).orEmpty()
    val items = rows.drop(1).mapNotNull { cells ->
        if (cells.size < 7) return@mapNotNull null
        ExamItem(
            term = requestedTerm ?: currentTerm,
            courseName = cells.getOrElse(1) { "" },
            schedule = cells.getOrNull(2),
            examMode = cells.getOrNull(3),
            remark = cells.getOrNull(4),
            registration = cells.getOrNull(5),
            status = cells.getOrNull(6),
        )
    }
    return ExamData(currentTerm = requestedTerm ?: currentTerm, availableTerms = options, items = items)
}

fun parseScores(html: String, requestedTerm: String? = null): ScoreData {
    val document = Jsoup.parse(html)
    val (options, currentTerm) = parseSelectOptions(document, "zxjxjhh")
    val rows = document.selectFirst("table.table, table")?.select("> tbody > tr, > tr").orEmpty()
    val items = rows.drop(1).mapNotNull { row ->
        val cellNodes = row.select("> th, > td")
        val cells = cellNodes.map { normalizeSpace(it.text()) }
        if (cells.size < 7) return@mapNotNull null
        ScoreItem(
            term = requestedTerm ?: cells.getOrNull(1) ?: currentTerm,
            courseName = cells.getOrElse(2) { cells.getOrElse(1) { "" } },
            credit = cells.getOrNull(3),
            score = cells.getOrNull(4),
            bonusScore = cells.getOrNull(5),
            teacher = cells.getOrNull(6),
            detail = cells.getOrNull(7),
            detailPath = cellNodes.getOrNull(7)?.let(::extractScoreDetailPath),
        )
    }
    return ScoreData(currentTerm = requestedTerm ?: currentTerm, availableTerms = options, items = items)
}

private fun extractScoreDetailPath(cell: Element): String? {
    val candidates = mutableListOf<String>()
    cell.select("a").forEach { link ->
        listOf("href", "data-url", "data-href").forEach { attr ->
            normalizeSpace(link.attr(attr)).takeIf { it.isNotBlank() }?.let(candidates::add)
        }
        normalizeSpace(link.attr("onclick")).takeIf { it.isNotBlank() }?.let(candidates::add)
    }
    candidates += cell.html()

    for (candidate in candidates) {
        cleanScoreDetailCandidate(candidate)?.let { return it }
        Regex("""["']([^"']*(?:score|cj|grade)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(candidate)
            .forEach { match ->
                cleanScoreDetailCandidate(match.groupValues[1])?.let { return it }
            }
    }
    return null
}

private fun cleanScoreDetailCandidate(value: String): String? {
    var cleaned = normalizeSpace(value)
    if (cleaned.isBlank() || cleaned == "#" || cleaned.startsWith("javascript:void", ignoreCase = true) || cleaned.startsWith("void(", ignoreCase = true)) {
        return null
    }
    if (cleaned.startsWith("javascript:", ignoreCase = true)) {
        cleaned = cleaned.substringAfter(':')
    }
    val urlMatch = Regex("""(https?://aa\.bjtu\.edu\.cn/[^\s"'<>]+|/[^\s"'<>]*(?:score|cj|grade)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
        .find(cleaned)
    if (urlMatch != null) return urlMatch.value.trimEnd(')', ';', ',')
    if (Regex("""(?:score|cj|grade)""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) && !Regex("""\s""").containsMatchIn(cleaned)) {
        return cleaned.trimEnd(')', ';', ',')
    }
    return null
}

fun parseScoreDetail(html: String): ScoreDetailData {
    val document = Jsoup.parse(html)
    val title = scoreDetailTitle(document)
    val fields = mutableListOf<ScoreDetailField>()
    val tables = mutableListOf<ScoreDetailTable>()
    val seenFields = mutableSetOf<Pair<String, String>>()

    fun addField(label: String, value: String) {
        val cleanedLabel = normalizeSpace(label).trimEnd(':', '：')
        val cleanedValue = normalizeSpace(value)
        if (cleanedLabel.isBlank() || cleanedValue.isBlank()) return
        val key = cleanedLabel to cleanedValue
        if (!seenFields.add(key)) return
        fields += ScoreDetailField(label = cleanedLabel, value = cleanedValue)
    }

    document.select("table").forEach { table ->
        val rows = tableRows(table)
        if (rows.isEmpty()) return@forEach

        rows.forEach { row ->
            if (row.size >= 2 && row.size % 2 == 0) {
                row.chunked(2).forEach { pair ->
                    if (pair[0].length <= 32) addField(pair[0], pair[1])
                }
            }
        }

        val headerCells = table.selectFirst("tr")?.select("> th, > td").orEmpty()
        val hasHeader = headerCells.isNotEmpty() && headerCells.all { it.tagName().equals("th", ignoreCase = true) }
        val headers = if (hasHeader) rows.first() else emptyList()
        val bodyRows = if (hasHeader) rows.drop(1) else rows
        val isFieldTable = !hasHeader && rows.all { row -> row.size >= 2 && row.size % 2 == 0 }
        if (bodyRows.isNotEmpty() && !isFieldTable) {
            tables += ScoreDetailTable(
                title = tableTitle(table),
                headers = headers,
                rows = bodyRows,
            )
        }
    }

    val rawText = normalizeSpace(document.text()).let { if (it.length > 4000) "${it.take(4000)}..." else it }
    return ScoreDetailData(
        title = title,
        fields = fields,
        tables = tables,
        rawText = rawText.ifBlank { null },
    )
}

private fun scoreDetailTitle(document: Element): String? {
    listOf(".modal-title", "h1", "h2", "h3", "legend", "title").forEach { selector ->
        val text = normalizeSpace(document.selectFirst(selector)?.text())
        if (text.isNotBlank()) return text
    }
    return null
}

private fun tableTitle(table: Element): String? {
    normalizeSpace(table.selectFirst("caption")?.text()).takeIf { it.isNotBlank() }?.let { return it }
    val heading = table.previousElementSiblings().firstOrNull { sibling ->
        sibling.tagName().lowercase() in setOf("h1", "h2", "h3", "h4", "legend")
    }
    return normalizeSpace(heading?.text()).takeIf { it.isNotBlank() }
}

fun parseStudentStatusProfile(html: String): StudentProfileData {
    val document = Jsoup.parse(html)
    val avatarUrl = document.select("img")
        .mapNotNull { normalizeSpace(it.attr("src")).takeIf { src -> src.isNotBlank() && !src.endsWith("/user.jpg") } }
        .firstOrNull()
    val sections = mutableListOf<ProfileSection>()
    val allFields = mutableListOf<ProfileField>()
    var currentTitle = "Profile"
    var currentSectionFields = mutableListOf<ProfileField>()

    fun flushSection() {
        if (currentSectionFields.isNotEmpty()) {
            sections += ProfileSection(currentTitle, currentSectionFields.toList())
            currentSectionFields = mutableListOf()
        }
    }

    fun addField(label: String, value: String) {
        val cleanedLabel = label.trim().trimEnd(':')
        if (cleanedLabel.isBlank()) return
        val field = ProfileField(cleanedLabel, value)
        allFields += field
        currentSectionFields += field
    }

    document.select("table.table, table").forEach { table ->
        table.select("tr").forEach { row ->
            val cells = row.select("> th, > td")
            if (cells.isEmpty()) return@forEach
            val texts = cells.map { normalizeSpace(it.text()) }
            val visible = texts.filter { it.isNotBlank() }
            if (visible.size == 1 && (cells.size == 1 || cells.first().hasAttr("colspan"))) {
                flushSection()
                currentTitle = visible.first()
                return@forEach
            }
            val pairTexts = cells.zip(texts).filterNot { (cell, _) -> cell.selectFirst("img") != null }.map { it.second }
            var index = 0
            while (index + 1 < pairTexts.size) {
                addField(pairTexts[index], pairTexts[index + 1])
                index += 2
            }
        }
        flushSection()
    }

    fun fieldByNeedle(vararg needles: String): String? =
        allFields.firstOrNull { field -> needles.any { field.label.contains(it, ignoreCase = true) } }?.value

    return StudentProfileData(
        name = fieldByNeedle("name", "名"),
        studentId = fieldByNeedle("student", "id", "no", "号"),
        gender = fieldByNeedle("gender", "sex"),
        birthday = fieldByNeedle("birth"),
        college = fieldByNeedle("college", "academy", "院"),
        major = fieldByNeedle("major", "业"),
        className = fieldByNeedle("class"),
        grade = fieldByNeedle("grade"),
        campus = fieldByNeedle("campus"),
        avatarUrl = avatarUrl,
        fields = allFields,
        sections = sections.ifEmpty { listOf(ProfileSection("Profile", allFields)) },
    )
}

fun isPassingScore(score: String?): Boolean {
    val value = normalizeSpace(score)
    if (value.isBlank()) return false
    parseCredit(value)?.let { return it >= 60.0 }
    val lower = value.lowercase()
    if (listOf("fail", "not pass", "unqualified").any { lower.contains(it) }) return false
    val grade = Regex("""(^|[^A-Z])([A-D][+-]?|F)($|[^A-Z])""").find(value.uppercase())?.groupValues?.get(2)
    if (grade != null) return grade in setOf("A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D")
    return listOf("pass", "qualified").any { lower.contains(it) }
}

private fun scoreToProgressCourse(item: ScoreItem): AcademicProgressCourse =
    AcademicProgressCourse(
        term = item.term,
        courseName = item.courseName,
        credit = parseCredit(item.credit),
        score = item.score,
        status = if (isPassingScore(item.score)) "passed" else "attention",
        detail = item.detail,
    )

private fun computeCreditSummary(courses: List<AcademicProgressCourse>, targetCredits: Double? = null): CreditSummary {
    val attempted = courses.sumOf { it.credit ?: 0.0 }
    val passed = courses.filter { it.status == "passed" }.sumOf { it.credit ?: 0.0 }
    val failed = attempted - passed
    val denominator = targetCredits?.takeIf { it > 0 } ?: attempted
    val rate = if (denominator > 0) (passed / denominator * 100.0).coerceAtMost(100.0) else 0.0
    return CreditSummary(
        courseCount = courses.size,
        passedCourseCount = courses.count { it.status == "passed" },
        failedCourseCount = courses.count { it.status != "passed" },
        attemptedCredits = "%.2f".format(attempted).toDouble(),
        passedCredits = "%.2f".format(passed).toDouble(),
        failedCredits = "%.2f".format(failed).toDouble(),
        targetCredits = targetCredits,
        completionRate = "%.1f".format(rate).toDouble(),
    )
}

private fun makeCreditBucket(name: String, required: String?, earned: String?, parent: String? = null): CreditBucket? {
    val cleaned = normalizeSpace(name)
    if (cleaned.isBlank()) return null
    val requiredCredits = parseCredit(required)
    val earnedCredits = parseCredit(earned) ?: 0.0
    if (requiredCredits == null && earnedCredits == 0.0) return null
    val pending = requiredCredits?.let { (it - earnedCredits).coerceAtLeast(0.0) }
    val rate = requiredCredits?.takeIf { it > 0 }?.let { (earnedCredits / it * 100.0).coerceAtMost(100.0) }
    return CreditBucket(
        name = cleaned,
        requiredCredits = requiredCredits,
        earnedCredits = "%.2f".format(earnedCredits).toDouble(),
        pendingCredits = pending?.let { "%.2f".format(it).toDouble() },
        completionRate = rate?.let { "%.1f".format(it).toDouble() },
        parent = parent,
    )
}

fun parseAcademicProgressDetailPath(html: String): String? {
    val document = Jsoup.parse(html)
    document.select("a[href]").forEach { link ->
        val href = normalizeSpace(link.attr("href"))
        if (href.contains("stustudyview")) return href
    }
    return Regex("""(/school_census/schooltraininfo/stustudyview/\d+/)""").find(html)?.groupValues?.get(1)
}

fun parseAcademicProgress(html: String): AcademicProgressData {
    val document = Jsoup.parse(html)
    var mergedBuckets = emptyList<CreditBucket>()
    var detailBuckets = emptyList<CreditBucket>()
    var courses = emptyList<AcademicProgressCourse>()

    document.select("table").forEach { table ->
        val rows = tableRows(table)
        if (rows.isEmpty()) return@forEach
        val headers = rows.first()
        val headerText = headers.joinToString(" ")
        when {
            headerText.contains("merge", ignoreCase = true) || rows.first().size == 3 -> {
                mergedBuckets = rows.drop(1).mapNotNull { row ->
                    if (row.size < 3) null else makeCreditBucket(row[0], row[1], row[2])
                }
            }
            rows.first().size >= 4 && rows.drop(1).any { it.size >= 4 } -> {
                var parent: String? = null
                detailBuckets = rows.drop(1).mapNotNull { row ->
                    when {
                        row.size >= 4 -> {
                            parent = row[0]
                            makeCreditBucket(row[1], row[2], row[3], parent)
                        }
                        row.size >= 3 -> makeCreditBucket(row[0], row[1], row[2], parent)
                        else -> null
                    }
                }
            }
            headerText.contains("score", ignoreCase = true) || rows.first().size >= 6 -> {
                val termIndex = headerIndex(headers, "term", "学期")
                val courseIndex = headerIndex(headers, "course", "课程") ?: 2.coerceAtMost(headers.lastIndex)
                val creditIndex = headerIndex(headers, "credit", "学分")
                val examIndex = headerIndex(headers, "exam", "考试")
                val scoreIndex = headerIndex(headers, "score", "成绩")
                val groupIndex = headerIndex(headers, "group", "课组")
                courses = rows.drop(1).mapNotNull { row ->
                    if (row.size <= courseIndex) return@mapNotNull null
                    val rawCourse = row[courseIndex]
                    val match = Regex("""^([A-Z]\d+[A-Z]?)\s+(.+)$""").find(rawCourse)
                    val score = scoreIndex?.let { row.getOrNull(it) }
                    AcademicProgressCourse(
                        term = termIndex?.let { row.getOrNull(it) },
                        courseCode = match?.groupValues?.get(1),
                        courseName = match?.groupValues?.get(2) ?: rawCourse,
                        credit = creditIndex?.let { parseCredit(row.getOrNull(it)) },
                        examDate = examIndex?.let { row.getOrNull(it) },
                        score = score,
                        status = if (isPassingScore(score)) "passed" else "attention",
                        groupInfo = groupIndex?.let { row.getOrNull(it) },
                        source = "academic_progress",
                    )
                }
            }
        }
    }

    val buckets = mergedBuckets.ifEmpty { detailBuckets }
    val target = buckets.sumOf { it.requiredCredits ?: 0.0 }.takeIf { it > 0 }
    val earned = buckets.sumOf { it.earnedCredits }
    val missing = target?.let { (it - earned).coerceAtLeast(0.0) } ?: 0.0
    val rate = target?.takeIf { it > 0 }?.let { (earned / it * 100.0).coerceAtMost(100.0) } ?: 0.0
    return AcademicProgressData(
        summary = CreditSummary(
            courseCount = courses.size,
            passedCourseCount = courses.count { it.status == "passed" },
            failedCourseCount = buckets.count { bucket -> bucket.requiredCredits != null && bucket.earnedCredits < bucket.requiredCredits },
            attemptedCredits = "%.2f".format(earned).toDouble(),
            passedCredits = "%.2f".format(earned).toDouble(),
            failedCredits = "%.2f".format(missing).toDouble(),
            targetCredits = target,
            completionRate = "%.1f".format(rate).toDouble(),
        ),
        buckets = buckets,
        mergedBuckets = mergedBuckets,
        detailBuckets = detailBuckets,
        courses = courses,
    )
}

fun parseEmptyRooms(html: String, requestedQuery: Map<String, String?> = emptyMap()): EmptyRoomData {
    val document = Jsoup.parse(html)
    val (termOptions, selectedTerm) = parseSelectOptions(document, "zxjxjhh")
    val (weekOptions, selectedWeek) = parseSelectOptions(document, "zc")
    val (buildingOptions, selectedBuilding) = parseSelectOptions(document, "jxlh", includeBlank = true)
    val formQuery = mapOf(
        "term" to (selectedTerm ?: parseInputValue(document, "zxjxjhh")),
        "week" to (selectedWeek ?: parseInputValue(document, "zc")),
        "building" to (selectedBuilding ?: parseInputValue(document, "jxlh")),
        "room" to parseInputValue(document, "jash"),
    ).filterValues { !it.isNullOrBlank() }
    val query = formQuery + requestedQuery.filterValues { !it.isNullOrBlank() }
    fun emptyData(
        days: List<String> = emptyList(),
        periods: List<Int> = emptyList(),
        slots: List<EmptyRoomSlotHeader> = emptyList(),
        rooms: List<EmptyRoomRow> = emptyList(),
    ) = EmptyRoomData(
        query = query,
        availableTerms = termOptions,
        availableWeeks = weekOptions,
        availableBuildings = buildingOptions,
        days = days,
        periods = periods,
        slots = slots,
        rooms = rooms,
    )

    val table = document.selectFirst("table.table, table") ?: return emptyData()
    val rows = table.select("> tbody > tr, > tr")
    if (rows.size < 2) return emptyData()

    val slotDays = mutableListOf<Pair<String, String?>>()
    rows[0].select("> th").drop(1).forEach { cell ->
        val parts = normalizeSpace(cell.text()).split(" ").filter { it.isNotBlank() }
        val day = parts.getOrNull(0).orEmpty()
        val date = parts.getOrNull(1)
        val colspan = cell.attr("colspan").toIntOrNull() ?: 1
        repeat(colspan) { slotDays += day to date }
    }

    val slots = mutableListOf<EmptyRoomSlotHeader>()
    val periods = linkedSetOf<Int>()
    rows[1].select("> th, > td").drop(1).forEachIndexed { index, cell ->
        val period = normalizeSpace(cell.text()).toIntOrNull() ?: index + 1
        val (day, date) = slotDays.getOrNull(index) ?: ("" to null)
        slots += EmptyRoomSlotHeader(day = day, date = date, period = period)
        periods += period
    }

    val rooms = rows.drop(2).mapNotNull { row ->
        val cells = row.select("> th, > td")
        if (cells.size < 2) return@mapNotNull null
        val roomText = normalizeSpace(cells.firstOrNull()?.text().orEmpty())
        if (roomText.isBlank()) return@mapNotNull null
        val seat = Regex("""\(([^)]+)\)""").find(roomText)?.groupValues?.get(1)
        val cellStates = cells.drop(1).map { cell ->
            val style = cell.attr("style").lowercase()
            val text = normalizeSpace(cell.text())
            classifyEmptyRoomCell(style, text)
        }
        EmptyRoomRow(
            room = roomText.substringBefore(" "),
            seatLabel = seat,
            availability = cellStates.map { it == EmptyRoomCellFree },
            cellStates = cellStates,
        )
    }

    val days = slots.map { normalizeSpace(listOfNotNull(it.day, it.date).joinToString(" ")) }
        .filter { it.isNotBlank() }
        .distinct()
    return emptyData(days = days, periods = periods.toList(), slots = slots, rooms = rooms)
}

private const val EmptyRoomCellFree = "free"
private const val EmptyRoomCellBusy = "busy"
private const val EmptyRoomCellNotice = "notice"

private fun classifyEmptyRoomCell(style: String, text: String): String {
    val normalizedStyle = style.replace(Regex("""\s+"""), "")
    return when {
        isWhiteCellStyle(normalizedStyle) -> EmptyRoomCellFree
        isNoticeCellStyle(normalizedStyle) -> EmptyRoomCellNotice
        isBusyCellStyle(normalizedStyle) -> EmptyRoomCellBusy
        normalizedStyle.isNotBlank() -> EmptyRoomCellNotice
        listOf("occupied", "busy", "unavailable", "占用", "有课", "不可").any { text.contains(it, ignoreCase = true) } -> EmptyRoomCellBusy
        listOf("free", "available", "空闲", "无课", "可用").any { text.contains(it, ignoreCase = true) } -> EmptyRoomCellFree
        else -> if (text.isBlank()) EmptyRoomCellFree else EmptyRoomCellBusy
    }
}

private fun isWhiteCellStyle(style: String): Boolean =
    style.contains("white") ||
        style.contains("rgb(255,255,255)") ||
        containsHexColor(style, "#fff") ||
        containsHexColor(style, "#ffffff")

private fun isNoticeCellStyle(style: String): Boolean =
    style.contains("yellow") || containsHexColor(style, "#ff0") || containsHexColor(style, "#ffff00")

private fun isBusyCellStyle(style: String): Boolean =
    style.contains("red") ||
        containsHexColor(style, "#f99") ||
        containsHexColor(style, "#ff9999") ||
        containsHexColor(style, "#e46868")

private fun containsHexColor(style: String, color: String): Boolean =
    Regex("""${Regex.escape(color)}(?![0-9a-f])""").containsMatchIn(style)

fun parseScorecardProgress(scorecardHtml: String, scores: ScoreData? = null): AcademicProgressData {
    val courses = scores?.items.orEmpty().map(::scoreToProgressCourse)
    return AcademicProgressData(currentTerm = scores?.currentTerm, summary = computeCreditSummary(courses), courses = courses)
}
