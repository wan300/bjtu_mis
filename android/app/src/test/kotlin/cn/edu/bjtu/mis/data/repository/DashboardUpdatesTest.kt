package cn.edu.bjtu.mis.data.repository

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.course.COURSE_RESOURCES_OVERVIEW_SCOPE
import cn.edu.bjtu.mis.model.CourseResourceItem
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class DashboardUpdatesTest {
    @Test
    fun firstFetchBuildsBaselineWithoutUpdates() {
        val summary = buildModuleUpdateSummary(
            moduleKey = ModuleKeys.Homework,
            oldPayloadJson = null,
            newPayloadJson = envelopeJson(ModuleKeys.Homework, HomeworkData(items = listOf(homework(1, "Lab")))),
            syncedAt = SyncedAt,
        )

        assertEquals(ModuleKeys.Homework, summary?.moduleKey)
        assertTrue(summary?.items.orEmpty().isEmpty())
    }

    @Test
    fun unchangedVisibleFieldsProduceNoUpdates() {
        val oldPayload = envelopeJson(
            ModuleKeys.Exams,
            ExamData(items = listOf(exam("Math", schedule = "2026-06-25 09:00"))),
            termParams(),
        )

        val summary = buildModuleUpdateSummary(
            moduleKey = ModuleKeys.Exams,
            oldPayloadJson = oldPayload,
            newPayloadJson = oldPayload,
            syncedAt = SyncedAt,
        )

        assertTrue(summary?.items.orEmpty().isEmpty())
    }

    @Test
    fun detectsAddedAndModifiedExams() {
        val oldPayload = envelopeJson(
            ModuleKeys.Exams,
            ExamData(items = listOf(exam("Math", schedule = "2026-06-25 09:00"))),
            termParams(),
        )
        val newPayload = envelopeJson(
            ModuleKeys.Exams,
            ExamData(
                items = listOf(
                    exam("Math", schedule = "2026-06-26 09:00"),
                    exam("Physics", schedule = "2026-06-27 14:00"),
                ),
            ),
            termParams(),
        )

        val summary = buildModuleUpdateSummary(ModuleKeys.Exams, oldPayload, newPayload, SyncedAt)

        assertEquals(listOf("modified", "added"), summary?.items?.map { it.changeType })
        assertEquals(listOf("Math", "Physics"), summary?.items?.map { it.title })
    }

    @Test
    fun courseResourcesOnlyCompareOverviewScope() {
        val oldItem = courseResource("1", name = "slides.pdf")
        val newItem = courseResource("1", name = "slides-v2.pdf")
        val oldPayload = envelopeJson(ModuleKeys.CourseResources, CourseResourcesData(resources = listOf(oldItem)), termParams())
        val newPayload = envelopeJson(ModuleKeys.CourseResources, CourseResourcesData(resources = listOf(newItem)), termParams())

        val ignored = buildModuleUpdateSummary(ModuleKeys.CourseResources, oldPayload, newPayload, SyncedAt)

        assertTrue(ignored?.items.orEmpty().isEmpty())

        val scopedOldPayload = envelopeJson(
            ModuleKeys.CourseResources,
            CourseResourcesData(resources = listOf(oldItem)),
            overviewCourseResourceParams(),
        )
        val scopedNewPayload = envelopeJson(
            ModuleKeys.CourseResources,
            CourseResourcesData(resources = listOf(newItem)),
            overviewCourseResourceParams(),
        )

        val compared = buildModuleUpdateSummary(ModuleKeys.CourseResources, scopedOldPayload, scopedNewPayload, SyncedAt)

        assertEquals(listOf("modified"), compared?.items?.map { it.changeType })
        assertEquals("slides-v2.pdf", compared?.items?.single()?.title)
    }

    @Test
    fun overviewHighlightsPrioritizeDueHomeworkAndDeduplicateHomeworkUpdates() {
        val now = LocalDateTime.of(2026, 6, 23, 10, 0)
        val urgentHomework = homework(1, "Report", dueAt = "2026-06-23 18:00")
        val normalHomework = homework(2, "Reading", dueAt = "2026-06-25 09:00")
        val homeworkSummary = ModuleUpdateSummary(
            moduleKey = ModuleKeys.Homework,
            syncedAt = SyncedAt,
            items = listOf(
                ModuleUpdateItem(
                    moduleKey = ModuleKeys.Homework,
                    itemKey = homeworkUpdateKey(urgentHomework),
                    changeType = "modified",
                    title = urgentHomework.title,
                    subtitle = "Software · 截止 ${urgentHomework.dueAt}",
                    route = ModuleKeys.Homework,
                    occurredAt = SyncedAt,
                ),
            ),
        )
        val examSummary = ModuleUpdateSummary(
            moduleKey = ModuleKeys.Exams,
            syncedAt = SyncedAt,
            items = listOf(updateItem(ModuleKeys.Exams, "exam-1", "Exam update")),
        )
        val scoreSummary = ModuleUpdateSummary(
            moduleKey = ModuleKeys.Scores,
            syncedAt = SyncedAt,
            items = listOf(updateItem(ModuleKeys.Scores, "score-1", "Score update")),
        )

        val highlights = buildOverviewHighlights(
            homework = listOf(normalHomework, urgentHomework),
            summaries = listOf(scoreSummary, homeworkSummary, examSummary),
            now = now,
            nowInstant = Instant.parse(SyncedAt),
            maxItems = 5,
        )

        assertEquals(listOf("Report", "Reading", "Exam update", "Score update"), highlights.items.map { it.title })
        assertTrue(highlights.items.first().urgent)
        assertTrue(highlights.items.first().updated)
        assertFalse(highlights.items.any { it.key.startsWith("update:${ModuleKeys.Homework}") })
    }

    @Test
    fun overviewHighlightsReportHiddenCount() {
        val summaries = (1..7).map {
            ModuleUpdateSummary(
                moduleKey = ModuleKeys.Scores,
                syncedAt = SyncedAt,
                items = listOf(updateItem(ModuleKeys.Scores, "score-$it", "Score $it")),
            )
        }

        val highlights = buildOverviewHighlights(emptyList(), summaries, nowInstant = Instant.parse(SyncedAt), maxItems = 5)

        assertEquals(5, highlights.items.size)
        assertEquals(2, highlights.remainingCount)
    }

    @Test
    fun overviewHighlightsIncludeUpdatesAtThreeDayBoundary() {
        val summary = ModuleUpdateSummary(
            moduleKey = ModuleKeys.Scores,
            syncedAt = SyncedAt,
            items = listOf(
                updateItem(
                    ModuleKeys.Scores,
                    "score-boundary",
                    "Boundary score",
                    occurredAt = "2026-06-20T10:00:00Z",
                ),
            ),
        )

        val highlights = buildOverviewHighlights(
            homework = emptyList(),
            summaries = listOf(summary),
            nowInstant = Instant.parse(SyncedAt),
            maxItems = 5,
        )

        assertEquals(listOf("Boundary score"), highlights.items.map { it.title })
        assertEquals(0, highlights.remainingCount)
    }

    @Test
    fun overviewHighlightsExcludeUpdatesOlderThanThreeDays() {
        val summary = ModuleUpdateSummary(
            moduleKey = ModuleKeys.Scores,
            syncedAt = SyncedAt,
            items = listOf(
                updateItem(
                    ModuleKeys.Scores,
                    "score-stale",
                    "Stale score",
                    occurredAt = "2026-06-20T09:59:59Z",
                ),
            ),
        )

        val highlights = buildOverviewHighlights(
            homework = emptyList(),
            summaries = listOf(summary),
            nowInstant = Instant.parse(SyncedAt),
            maxItems = 5,
        )

        assertTrue(highlights.items.isEmpty())
        assertEquals(0, highlights.remainingCount)
    }

    @Test
    fun staleUpdatesDoNotContributeToHiddenCount() {
        val recent = (1..6).map {
            ModuleUpdateSummary(
                moduleKey = ModuleKeys.Scores,
                syncedAt = SyncedAt,
                items = listOf(updateItem(ModuleKeys.Scores, "score-recent-$it", "Recent $it")),
            )
        }
        val stale = (1..3).map {
            ModuleUpdateSummary(
                moduleKey = ModuleKeys.Exams,
                syncedAt = "2026-06-20T09:59:59Z",
                items = listOf(
                    updateItem(
                        ModuleKeys.Exams,
                        "exam-stale-$it",
                        "Stale $it",
                        occurredAt = "2026-06-20T09:59:59Z",
                    ),
                ),
            )
        }

        val highlights = buildOverviewHighlights(
            homework = emptyList(),
            summaries = recent + stale,
            nowInstant = Instant.parse(SyncedAt),
            maxItems = 5,
        )

        assertEquals(5, highlights.items.size)
        assertEquals(1, highlights.remainingCount)
        assertFalse(highlights.items.any { it.title.startsWith("Stale") })
    }

    @Test
    fun nearDueHomeworkStillAppearsWhenUpdatesAreStale() {
        val now = LocalDateTime.of(2026, 6, 23, 10, 0)
        val homework = homework(1, "Report", dueAt = "2026-06-24 10:00")
        val staleSummary = ModuleUpdateSummary(
            moduleKey = ModuleKeys.Scores,
            syncedAt = "2026-06-20T09:59:59Z",
            items = listOf(
                updateItem(
                    ModuleKeys.Scores,
                    "score-stale",
                    "Stale score",
                    occurredAt = "2026-06-20T09:59:59Z",
                ),
            ),
        )

        val highlights = buildOverviewHighlights(
            homework = listOf(homework),
            summaries = listOf(staleSummary),
            now = now,
            nowInstant = Instant.parse(SyncedAt),
            maxItems = 5,
        )

        assertEquals(listOf("Report"), highlights.items.map { it.title })
        assertEquals(0, highlights.remainingCount)
    }

    private fun homework(
        id: Int,
        title: String,
        dueAt: String? = "2026-06-30 23:59",
    ): HomeworkItem =
        HomeworkItem(
            homeworkId = id,
            course = "Software",
            courseId = 10,
            title = title,
            openedAt = "2026-06-20 08:00",
            dueAt = dueAt,
            status = "open",
            subType = 0,
        )

    private fun exam(course: String, schedule: String): ExamItem =
        ExamItem(term = "2025-2026-2-2", courseName = course, schedule = schedule, examMode = "闭卷")

    private fun courseResource(id: String, name: String): CourseResourceItem =
        CourseResourceItem(
            resourceId = id,
            rpId = id,
            courseId = 10,
            courseName = "Software",
            name = name,
            categoryLabel = "课件",
        )

    private fun updateItem(
        moduleKey: String,
        itemKey: String,
        title: String,
        occurredAt: String = SyncedAt,
    ): ModuleUpdateItem =
        ModuleUpdateItem(
            moduleKey = moduleKey,
            itemKey = itemKey,
            changeType = "added",
            title = title,
            subtitle = "Updated",
            route = moduleKey,
            occurredAt = occurredAt,
        )

    private inline fun <reified T> envelopeJson(
        module: String,
        data: T,
        sourceParams: JsonObject = buildJsonObject {},
    ): String =
        AppJson.encodeToString(
            ModuleEnvelope(
                module = module,
                syncedAt = SyncedAt,
                sourceSystem = "test",
                coverage = CoverageLevel.Verified,
                sourceParams = sourceParams,
                data = data,
            ),
        )

    private fun termParams(): JsonObject =
        buildJsonObject { put("term", "2025-2026-2-2") }

    private fun overviewCourseResourceParams(): JsonObject =
        buildJsonObject {
            put("term", "2025-2026-2-2")
            put("overview_scope", COURSE_RESOURCES_OVERVIEW_SCOPE)
            put("folder_id", "0")
            put("category_key", "all")
        }

    private companion object {
        const val SyncedAt = "2026-06-23T10:00:00Z"
    }
}
