package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.model.AcademicProgressCourse
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CourseEntry
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
import cn.edu.bjtu.mis.model.ScoreItem
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.model.TimetableData
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

fun parseSelectOptions(document: Element, fieldName: String): Pair<List<TermOption>, String?> {
    val select = document.selectFirst("select[name=$fieldName], select#$fieldName")
        ?: return emptyList<TermOption>() to null
    val options = select.select("option").mapNotNull { option ->
        val value = normalizeSpace(option.attr("value"))
        if (value.isBlank()) return@mapNotNull null
        TermOption(value = value, label = normalizeSpace(option.text()).ifBlank { value }, selected = option.hasAttr("selected"))
    }
    return options to (options.firstOrNull { it.selected }?.value ?: options.firstOrNull()?.value)
}

fun parseInputValue(document: Element, fieldName: String): String? =
    document.selectFirst("[name=$fieldName]")?.attr("value")?.let(::normalizeSpace)

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
    val rows = document.selectFirst("table.table, table")?.let(::tableRows).orEmpty()
    val items = rows.drop(1).mapNotNull { cells ->
        if (cells.size < 7) return@mapNotNull null
        ScoreItem(
            term = requestedTerm ?: cells.getOrNull(1) ?: currentTerm,
            courseName = cells.getOrElse(2) { cells.getOrElse(1) { "" } },
            credit = cells.getOrNull(3),
            score = cells.getOrNull(4),
            bonusScore = cells.getOrNull(5),
            teacher = cells.getOrNull(6),
            detail = cells.getOrNull(7),
        )
    }
    return ScoreData(currentTerm = requestedTerm ?: currentTerm, availableTerms = options, items = items)
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
    val table = document.selectFirst("table.table, table") ?: return EmptyRoomData(query = requestedQuery)
    val rows = table.select("> tbody > tr, > tr")
    if (rows.size < 2) return EmptyRoomData(query = requestedQuery)

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
        val roomText = normalizeSpace(cells.first().text())
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

    val query = requestedQuery.ifEmpty {
        mapOf(
            "term" to parseInputValue(document, "zxjxjhh"),
            "week" to parseInputValue(document, "zc"),
            "building" to parseInputValue(document, "jxlh"),
            "room" to parseInputValue(document, "jash"),
        )
    }
    val days = slots.map { normalizeSpace(listOfNotNull(it.day, it.date).joinToString(" ")) }
        .filter { it.isNotBlank() }
        .distinct()
    return EmptyRoomData(query = query, days = days, periods = periods.toList(), slots = slots, rooms = rooms)
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
