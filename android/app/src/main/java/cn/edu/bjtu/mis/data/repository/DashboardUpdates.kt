package cn.edu.bjtu.mis.data.repository

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.course.COURSE_RESOURCES_OVERVIEW_SCOPE
import cn.edu.bjtu.mis.data.db.ModuleUpdateSummaryEntity
import cn.edu.bjtu.mis.data.homework.HomeworkStatusKind
import cn.edu.bjtu.mis.data.homework.homeworkStatusKind
import cn.edu.bjtu.mis.data.homework.parseHomeworkDueAt
import cn.edu.bjtu.mis.model.CourseResourceItem
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class ModuleUpdateSummary(
    val moduleKey: String,
    val syncedAt: String,
    val items: List<ModuleUpdateItem> = emptyList(),
)

@Serializable
data class ModuleUpdateItem(
    val moduleKey: String,
    val itemKey: String,
    val changeType: String,
    val title: String,
    val subtitle: String,
    val route: String,
    val occurredAt: String,
)

data class OverviewHighlights(
    val items: List<DashboardHighlight>,
    val remainingCount: Int,
)

data class DashboardHighlight(
    val key: String,
    val title: String,
    val subtitle: String,
    val route: String,
    val tag: String,
    val urgent: Boolean = false,
    val updated: Boolean = false,
    internal val moduleKey: String,
    internal val priority: Int,
    internal val dueSort: Long? = null,
    internal val occurredAt: String? = null,
)

@PublishedApi
internal fun ModuleUpdateSummary.toEntity(): ModuleUpdateSummaryEntity =
    ModuleUpdateSummaryEntity(
        moduleKey = moduleKey,
        syncedAt = syncedAt,
        itemsJson = AppJson.encodeToString(items),
    )

internal fun ModuleUpdateSummaryEntity.toUpdateSummary(): ModuleUpdateSummary =
    ModuleUpdateSummary(
        moduleKey = moduleKey,
        syncedAt = syncedAt,
        items = runCatching { AppJson.decodeFromString<List<ModuleUpdateItem>>(itemsJson) }
            .getOrDefault(emptyList()),
    )

@PublishedApi
internal fun buildModuleUpdateSummary(
    moduleKey: String,
    oldPayloadJson: String?,
    newPayloadJson: String,
    syncedAt: String,
): ModuleUpdateSummary? =
    when (moduleKey) {
        ModuleKeys.Homework -> buildTypedSummary<HomeworkData, HomeworkItem>(
            moduleKey,
            oldPayloadJson,
            newPayloadJson,
            syncedAt,
            comparable = { old, new -> sameParam(old.sourceParams, new.sourceParams, "term") },
            items = { it.items },
            key = ::homeworkUpdateKey,
            fingerprint = ::homeworkFingerprint,
            title = { it.title },
            subtitle = ::homeworkUpdateSubtitle,
        )
        ModuleKeys.Exams -> buildTypedSummary<ExamData, ExamItem>(
            moduleKey,
            oldPayloadJson,
            newPayloadJson,
            syncedAt,
            comparable = { old, new -> sameParam(old.sourceParams, new.sourceParams, "term") },
            items = { it.items },
            key = ::examUpdateKey,
            fingerprint = ::examFingerprint,
            title = { it.courseName },
            subtitle = ::examUpdateSubtitle,
        )
        ModuleKeys.Scores -> buildTypedSummary<ScoreData, ScoreItem>(
            moduleKey,
            oldPayloadJson,
            newPayloadJson,
            syncedAt,
            comparable = { old, new ->
                sameParam(old.sourceParams, new.sourceParams, "term") &&
                    sameParam(old.sourceParams, new.sourceParams, "ctype")
            },
            items = { it.items },
            key = ::scoreUpdateKey,
            fingerprint = ::scoreFingerprint,
            title = { it.courseName },
            subtitle = ::scoreUpdateSubtitle,
        )
        ModuleKeys.CourseResources -> buildTypedSummary<CourseResourcesData, CourseResourceItem>(
            moduleKey,
            oldPayloadJson,
            newPayloadJson,
            syncedAt,
            comparable = { old, new ->
                param(old.sourceParams, "overview_scope") == COURSE_RESOURCES_OVERVIEW_SCOPE &&
                    param(new.sourceParams, "overview_scope") == COURSE_RESOURCES_OVERVIEW_SCOPE &&
                    sameParam(old.sourceParams, new.sourceParams, "term") &&
                    sameParam(old.sourceParams, new.sourceParams, "folder_id") &&
                    sameParam(old.sourceParams, new.sourceParams, "category_key")
            },
            items = { it.resources },
            key = ::courseResourceUpdateKey,
            fingerprint = ::courseResourceFingerprint,
            title = { it.name },
            subtitle = ::courseResourceUpdateSubtitle,
        )
        else -> null
    }

internal fun buildOverviewHighlights(
    homework: List<HomeworkItem>,
    summaries: List<ModuleUpdateSummary>,
    now: LocalDateTime = LocalDateTime.now(),
    nowInstant: Instant = Instant.now(),
    maxItems: Int = 5,
): OverviewHighlights {
    val homeworkUpdateKeys = summaries
        .flatMap { it.items }
        .filter { it.moduleKey == ModuleKeys.Homework }
        .map { it.itemKey }
        .toSet()

    val nearDueHomework = homework
        .mapNotNull { item ->
            val dueAt = parseHomeworkDueAt(item.dueAt) ?: return@mapNotNull null
            if (homeworkStatusKind(item, now) != HomeworkStatusKind.Open) return@mapNotNull null
            val remaining = Duration.between(now, dueAt)
            if (remaining.isNegative || remaining > Duration.ofHours(72)) return@mapNotNull null
            val urgent = remaining <= Duration.ofHours(24)
            val key = homeworkUpdateKey(item)
            DashboardHighlight(
                key = "due:$key",
                title = item.title,
                subtitle = listOfNotNull(
                    item.course.takeIf { it.isNotBlank() },
                    item.dueAt?.let { "截止 $it" },
                    if (urgent) "24小时内" else null,
                ).joinToString(" · "),
                route = ModuleKeys.Homework,
                tag = if (urgent) "紧急作业" else "临期作业",
                urgent = urgent,
                updated = key in homeworkUpdateKeys,
                moduleKey = ModuleKeys.Homework,
                priority = 0,
                dueSort = dueAt.toEpochSecond(ZoneOffset.UTC),
            )
        }
        .sortedBy { it.dueSort ?: Long.MAX_VALUE }

    val nearDueHomeworkKeys = nearDueHomework
        .map { it.key.removePrefix("due:") }
        .toSet()

    val updateCutoff = nowInstant.minus(RECENT_UPDATE_WINDOW)
    val updateHighlights = summaries
        .flatMap { it.items }
        .filter { isRecentUpdate(it.occurredAt, updateCutoff) }
        .filterNot { it.moduleKey == ModuleKeys.Homework && it.itemKey in nearDueHomeworkKeys }
        .map { item ->
            val priority = when (item.moduleKey) {
                ModuleKeys.Exams -> 1
                ModuleKeys.Scores -> 2
                ModuleKeys.CourseResources -> 3
                ModuleKeys.Homework -> 4
                else -> 9
            }
            DashboardHighlight(
                key = "update:${item.moduleKey}:${item.itemKey}:${item.occurredAt}",
                title = item.title,
                subtitle = item.subtitle,
                route = item.route,
                tag = changeTag(item),
                updated = true,
                moduleKey = item.moduleKey,
                priority = priority,
                occurredAt = item.occurredAt,
            )
        }

    val all = (nearDueHomework + updateHighlights)
        .sortedWith(
            compareBy<DashboardHighlight> { it.priority }
                .thenBy { it.dueSort ?: Long.MAX_VALUE }
                .thenByDescending { it.occurredAt.orEmpty() }
                .thenBy { it.title },
        )
    val limit = maxItems.coerceAtLeast(1)
    return OverviewHighlights(
        items = all.take(limit),
        remainingCount = (all.size - limit).coerceAtLeast(0),
    )
}

private val RECENT_UPDATE_WINDOW: Duration = Duration.ofDays(3)

private fun isRecentUpdate(occurredAt: String, cutoff: Instant): Boolean {
    val occurredAtInstant = runCatching { OffsetDateTime.parse(occurredAt).toInstant() }
        .getOrNull()
        ?: return false
    return !occurredAtInstant.isBefore(cutoff)
}

private inline fun <reified D, I> buildTypedSummary(
    moduleKey: String,
    oldPayloadJson: String?,
    newPayloadJson: String,
    syncedAt: String,
    comparable: (ModuleEnvelope<D>, ModuleEnvelope<D>) -> Boolean,
    items: (D) -> List<I>,
    noinline key: (I) -> String,
    noinline fingerprint: (I) -> String,
    noinline title: (I) -> String,
    noinline subtitle: (I) -> String,
): ModuleUpdateSummary {
    val newEnvelope = runCatching { AppJson.decodeFromString<ModuleEnvelope<D>>(newPayloadJson) }
        .getOrNull()
        ?: return ModuleUpdateSummary(moduleKey, syncedAt)
    val oldEnvelope = oldPayloadJson
        ?.let { runCatching { AppJson.decodeFromString<ModuleEnvelope<D>>(it) }.getOrNull() }
        ?: return ModuleUpdateSummary(moduleKey, syncedAt)
    if (!comparable(oldEnvelope, newEnvelope)) return ModuleUpdateSummary(moduleKey, syncedAt)

    val oldByKey = items(oldEnvelope.data).associateBy(key)
    val changes = items(newEnvelope.data).mapNotNull { item ->
        val itemKey = key(item)
        val old = oldByKey[itemKey]
        val changeType = when {
            old == null -> "added"
            fingerprint(old) != fingerprint(item) -> "modified"
            else -> return@mapNotNull null
        }
        ModuleUpdateItem(
            moduleKey = moduleKey,
            itemKey = itemKey,
            changeType = changeType,
            title = title(item).ifBlank { moduleLabel(moduleKey) },
            subtitle = subtitle(item),
            route = moduleKey,
            occurredAt = syncedAt,
        )
    }
    return ModuleUpdateSummary(moduleKey, syncedAt, changes)
}

private fun changeTag(item: ModuleUpdateItem): String {
    val suffix = if (item.changeType == "added") "新增" else "更新"
    return when (item.moduleKey) {
        ModuleKeys.Exams -> "考务$suffix"
        ModuleKeys.Scores -> "成绩$suffix"
        ModuleKeys.CourseResources -> "资料$suffix"
        ModuleKeys.Homework -> "作业$suffix"
        else -> suffix
    }
}

private fun moduleLabel(moduleKey: String): String =
    when (moduleKey) {
        ModuleKeys.Exams -> "考务"
        ModuleKeys.Scores -> "成绩"
        ModuleKeys.CourseResources -> "课程资料"
        ModuleKeys.Homework -> "作业"
        else -> moduleKey
    }

internal fun homeworkUpdateKey(item: HomeworkItem): String =
    item.homeworkId?.let { "id:$it" }
        ?: stableKey("course", item.courseId, "title", item.title, "opened", item.openedAt, "due", item.dueAt)

private fun homeworkFingerprint(item: HomeworkItem): String =
    stableKey(
        item.course,
        item.courseId,
        item.courseCode,
        item.title,
        item.contentExcerpt,
        item.requirementText,
        item.openedAt,
        item.dueAt,
        item.submittedAt,
        item.status,
        item.submissionStatus,
        item.canSubmit,
        item.canSubmitExplicit,
        item.contentType,
        item.isGroup,
        item.returnNum,
    )

private fun homeworkUpdateSubtitle(item: HomeworkItem): String =
    listOfNotNull(
        item.course.takeIf { it.isNotBlank() },
        item.dueAt?.let { "截止 $it" },
        item.submissionStatus?.takeIf { it.isNotBlank() } ?: item.status.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

private fun examUpdateKey(item: ExamItem): String =
    stableKey(item.term, item.courseName, item.registration)

private fun examFingerprint(item: ExamItem): String =
    stableKey(item.term, item.courseName, item.schedule, item.examMode, item.remark, item.registration, item.status)

private fun examUpdateSubtitle(item: ExamItem): String =
    listOfNotNull(item.schedule, item.examMode, item.status).joinToString(" · ")

private fun scoreUpdateKey(item: ScoreItem): String =
    item.detailPath?.takeIf { it.isNotBlank() }?.let { "detail:$it" }
        ?: stableKey(item.term, item.courseName, item.teacher)

private fun scoreFingerprint(item: ScoreItem): String =
    stableKey(item.term, item.courseName, item.credit, item.score, item.bonusScore, item.teacher, item.detail, item.detailPath)

private fun scoreUpdateSubtitle(item: ScoreItem): String =
    listOfNotNull(
        item.term,
        item.score?.let { "成绩 $it" },
        item.bonusScore?.let { "加分 $it" },
        item.teacher,
    ).joinToString(" · ")

private fun courseResourceUpdateKey(item: CourseResourceItem): String =
    stableKey(item.courseId, item.categoryKey, item.folderId, item.rpId.ifBlank { item.resourceId })

private fun courseResourceFingerprint(item: CourseResourceItem): String =
    stableKey(
        item.courseId,
        item.courseName,
        item.resourceId,
        item.rpId,
        item.resId,
        item.name,
        item.extension,
        item.size,
        item.uploadedAt,
        item.teacherName,
        item.downloadCount,
        item.clickCount,
        item.canDownload,
        item.folderId,
        item.categoryKey,
        item.categoryLabel,
    )

private fun courseResourceUpdateSubtitle(item: CourseResourceItem): String =
    listOfNotNull(
        item.courseName?.takeIf { it.isNotBlank() },
        item.categoryLabel.takeIf { it.isNotBlank() },
        item.size?.takeIf { it.isNotBlank() },
        item.uploadedAt?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

private fun sameParam(first: JsonObject, second: JsonObject, name: String): Boolean =
    param(first, name) == param(second, name)

private fun param(source: JsonObject, name: String): String =
    source[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun stableKey(vararg values: Any?): String =
    values.joinToString("\u001F") { normalizeStablePart(it) }

private fun normalizeStablePart(value: Any?): String =
    when (value) {
        null -> ""
        is String -> value.trim().replace(Regex("\\s+"), " ")
        else -> value.toString()
    }
