package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.model.HomeworkAttachment
import cn.edu.bjtu.mis.model.HomeworkItem
import org.junit.Assert.assertEquals
import org.junit.Test

class VeProviderTest {
    @Test
    fun mergeHomeworkItemsPrefersSubmittedDuplicateAndKeepsOrder() {
        val open = homework(id = 1, title = "实验一", submittedAt = null)
        val submitted = homework(id = 1, title = "实验一", submittedAt = "2026-05-18 10:00")
        val withoutId = homework(id = null, title = "课程报告", submittedAt = null)
        val duplicateWithoutId = homework(id = null, title = "课程报告", submittedAt = null)

        val merged = mergeHomeworkItems(listOf(open, withoutId, submitted, duplicateWithoutId))

        assertEquals(listOf(submitted, withoutId), merged)
        assertEquals("result.pdf", merged.first().attachments.single().filename)
    }

    @Test
    fun courseReplayUserIdCandidatesPreferReplayDetailId() {
        val candidates = courseReplayUserIdCandidates(
            detailUserId = " detail-user ",
            contextUserId = " platform-user ",
            preferredUserId = " cached-user ",
            listenUserId = " listen-user ",
        )

        assertEquals(listOf("detail-user", "platform-user", "cached-user", "listen-user"), candidates)
    }

    @Test
    fun courseReplayUserIdCandidatesTrimAndDeduplicate() {
        val candidates = courseReplayUserIdCandidates(
            detailUserId = " ",
            contextUserId = " 1001 ",
            preferredUserId = "1001",
            listenUserId = "1001",
        )

        assertEquals(listOf("1001"), candidates)
    }

    @Test
    fun courseReplayDetailParamsIncludeLessonIdentity() {
        val params = courseReplayDetailParams(
            courseSchedId = " 1774916 ",
            userId = " 136786 ",
            videoId = " 9E2657F72D5E450FAB4CDF1E6725F15E ",
            timeTableId = " 559F6748116A43D399DE404B5ED28ED6 ",
        )

        assertEquals("toDisplyCourseSchedDetail", params["method"])
        assertEquals("1774916", params["courseSchedId"])
        assertEquals("136786", params["userId"])
        assertEquals("1", params["userLevel"])
        assertEquals("9E2657F72D5E450FAB4CDF1E6725F15E", params["videoId"])
        assertEquals("559F6748116A43D399DE404B5ED28ED6", params["uuid"])
        assertEquals("559F6748116A43D399DE404B5ED28ED6", params["timeTableId"])
        assertEquals("559F6748116A43D399DE404B5ED28ED6", params["timetableId"])
    }

    @Test
    fun courseReplayDetailParamsOmitBlankLessonIdentity() {
        val params = courseReplayDetailParams(
            courseSchedId = "1774916",
            userId = "136786",
            videoId = " ",
            timeTableId = "",
        )

        assertEquals(false, params.containsKey("videoId"))
        assertEquals(false, params.containsKey("uuid"))
        assertEquals(false, params.containsKey("timeTableId"))
        assertEquals(false, params.containsKey("timetableId"))
    }

    @Test
    fun courseResourceCategoryConfigsMatchCapturedVeModules() {
        val configs = courseResourceCategoryConfigs.associateBy { it.key }

        assertEquals("10450", configs.getValue("courseware").courseToPage)
        assertEquals("1", configs.getValue("courseware").docType)
        assertEquals("10451", configs.getValue("lesson_plan").courseToPage)
        assertEquals("5", configs.getValue("lesson_plan").docType)
        assertEquals("10453", configs.getValue("experiment").courseToPage)
        assertEquals("10", configs.getValue("experiment").docType)
    }

    @Test
    fun courseResourceAllCategoryExpandsToEveryConfiguredCategory() {
        assertEquals(
            listOf("courseware", "lesson_plan", "experiment"),
            courseResourceConfigsFor("all").map { it.key },
        )
        assertEquals(
            listOf("courseware", "lesson_plan", "experiment"),
            courseResourceConfigsFor(null).map { it.key },
        )
        assertEquals(listOf("experiment"), courseResourceConfigsFor("experiment").map { it.key })
    }

    private fun homework(id: Int?, title: String, submittedAt: String?): HomeworkItem =
        HomeworkItem(
            homeworkId = id,
            course = "软件工程",
            courseId = 100,
            courseCode = "M410001B",
            title = title,
            submittedAt = submittedAt,
            status = if (submittedAt == null) "open" else "done",
            subType = 0,
            attachments = if (submittedAt == null) {
                emptyList()
            } else {
                listOf(HomeworkAttachment("a1", "result.pdf"))
            },
        )
}
