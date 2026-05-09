package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParserTest {
    private val fixtures = sequenceOf(
        File("../../backend/tests/fixtures"),
        File("../../../backend/tests/fixtures"),
        File("backend/tests/fixtures"),
    ).first { it.exists() }

    @Test
    fun parseTimetableFixture() {
        val data = parseTimetable(text("timetable.html"))
        assertEquals(2, data.entries.size)
        assertEquals("M310005B", data.entries.first().courseCode)
    }

    @Test
    fun parseScoresFixture() {
        val data = parseScores(text("scores_main.html"))
        assertEquals(2, data.items.size)
        assertTrue(data.items.first().courseName.isNotBlank())
    }

    @Test
    fun parseEmptyRoomsFixture() {
        val data = parseEmptyRooms(text("empty_rooms.html"), mapOf("week" to "8"))
        assertEquals(4, data.slots.size)
        assertEquals(2, data.rooms.size)
        assertEquals(listOf(true, false, true, true), data.rooms[1].availability)
        assertEquals(listOf("free", "busy", "free", "free"), data.rooms[1].cellStates)
    }

    @Test
    fun parseEmptyRoomsCellStatesFromStyles() {
        val html = """
            <table class="table table-bordered">
              <tr>
                <th>星期</th>
                <th colspan="3">星期一 05月04日</th>
              </tr>
              <tr>
                <th>教室/节次</th>
                <th>1</th>
                <th>2</th>
                <th>3</th>
              </tr>
              <tr>
                <td>SY101 (90)</td>
                <td style="background-color: #e46868"></td>
                <td style="background-color: #fff"></td>
                <td style="background-color: #ffff00"></td>
              </tr>
            </table>
        """.trimIndent()

        val data = parseEmptyRooms(html, mapOf("week" to "8"))

        assertEquals(listOf(false, true, false), data.rooms.single().availability)
        assertEquals(listOf("busy", "free", "notice"), data.rooms.single().cellStates)
    }

    @Test
    fun parseCalendarAndHomeworkFixtures() {
        val (terms, currentTerm) = parseCalendarTerms(json("calendar_terms.json"))
        val calendar = parseCalendar(json("calendar_month.json"), "2026-04", currentTerm, terms)
        assertEquals("8", calendar.currentWeek)
        assertEquals(3, calendar.items.size)

        val courses = parseCourses(json("course_list.json"))
        val homework = parseHomeworkList(json("homework_open.json"), courses.first(), 0)
        assertEquals(1, homework.size)
        assertEquals("open", homework.first().status)
        assertEquals("M410001B", homework.first().courseCode)
    }

    @Test
    fun parseHomeworkStatusUsesSubmissionTime() {
        val course = parseCourses(json("course_list.json")).first()
        val payload = AppJson.parseToJsonElement(
            """
            {
              "courseNoteList": [
                {"id": 1, "title": "Open homework"},
                {"id": 2, "title": "Submitted homework", "subTime": "2026-04-10 23:05:56", "subStatus": "submitted"}
              ],
              "STATUS": "0"
            }
            """.trimIndent()
        ).jsonObject

        val homework = parseHomeworkList(payload, course, 0)

        assertEquals(listOf("open", "done"), homework.map { it.status })
        assertEquals(null, homework[0].submittedAt)
        assertEquals("2026-04-10 23:05:56", homework[1].submittedAt)
    }

    @Test
    fun parseCourseResourcesFixtureShape() {
        val payload = AppJson.parseToJsonElement(
            """
            {
              "bagList": [{"id": 8, "bag_name": "课堂材料"}],
              "resList": [{"rpId": 1001, "resId": 2001, "rpName": "GraphQL.pdf", "extName": "PDF", "stu_download": 2}],
              "STATUS": "0"
            }
            """.trimIndent()
        ).jsonObject
        val (folders, resources) = parseCourseResourceListing(payload, "0")
        assertEquals("8", folders.first().folderId)
        assertEquals("1001", resources.first().rpId)
        assertTrue(resources.first().canDownload)
    }

    private fun text(name: String): String = File(fixtures, name).readText(Charsets.UTF_8)

    private fun json(name: String) = AppJson.parseToJsonElement(text(name)).jsonObject
}
