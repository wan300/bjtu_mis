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
