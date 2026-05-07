package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.parser.parseAcademicProgress
import cn.edu.bjtu.mis.data.parser.parseAcademicProgressDetailPath
import cn.edu.bjtu.mis.data.parser.parseEmptyRooms
import cn.edu.bjtu.mis.data.parser.parseExams
import cn.edu.bjtu.mis.data.parser.parseScores
import cn.edu.bjtu.mis.data.parser.parseStudentStatusProfile
import cn.edu.bjtu.mis.data.parser.parseTimetable
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TimetableData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AaProvider(private val client: BjtuHttpClient) {
    suspend fun fetchTimetable(term: String? = null, week: String? = null): ModuleEnvelope<TimetableData> {
        val html = getText("/course_selection/courseselect/stuschedule/")
        return ModuleEnvelope(
            module = "timetable",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                term?.let { put("term", it) }
                week?.let { put("week", it) }
            },
            data = parseTimetable(html),
        )
    }

    suspend fun fetchExams(term: String? = null): ModuleEnvelope<ExamData> {
        val html = getText("/examine/examplanstudent/stulist/", mapOf("zxjxjhh" to term))
        val parsed = parseExams(html, term)
        return ModuleEnvelope(
            module = "exams",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject { parsed.currentTerm?.let { put("term", it) } },
            data = parsed,
        )
    }

    suspend fun fetchScores(term: String? = null, ctype: String? = null): ModuleEnvelope<ScoreData> {
        val params = mapOf("zxjxjhh" to term, "ctype" to ctype)
        val html = getText("/score/scores/stu/view/", params)
        val parsed = parseScores(html, term)
        return ModuleEnvelope(
            module = "scores",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                parsed.currentTerm?.let { put("term", it) }
                ctype?.let { put("ctype", it) }
            },
            data = parsed,
        )
    }

    suspend fun fetchHistoryScores(term: String? = null): ModuleEnvelope<ScoreData> =
        fetchScores(term = term, ctype = "ln").copy(
            module = "history_scores",
            sourceParams = buildJsonObject {
                term?.let { put("term", it) }
                put("ctype", "ln")
            },
        )

    suspend fun fetchStudentProfile(): ModuleEnvelope<StudentProfileData> {
        val html = getText("/school_census/schoolcensus/stuview/")
        val parsed = parseStudentStatusProfile(html).let { profile ->
            if (profile.avatarUrl != null && !profile.avatarUrl.startsWith("http") && !profile.avatarUrl.startsWith("data:")) {
                profile.copy(avatarUrl = "${ProviderConstants.AA_BASE_URL}/${profile.avatarUrl.trimStart('/')}")
            } else {
                profile
            }
        }
        return ModuleEnvelope(
            module = "profile",
            sourceSystem = "aa",
            coverage = if (parsed.fields.isNotEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            data = parsed,
        )
    }

    suspend fun fetchAcademicProgress(): ModuleEnvelope<AcademicProgressData> {
        val listHtml = getText("/school_census/schooltraininfo/studylist/")
        val detailPath = parseAcademicProgressDetailPath(listHtml)
        val parsed = if (detailPath.isNullOrBlank()) {
            parseAcademicProgress("")
        } else {
            parseAcademicProgress(getText(detailPath))
        }
        return ModuleEnvelope(
            module = "academic_progress",
            sourceSystem = "aa",
            coverage = if (parsed.buckets.isNotEmpty() || parsed.courses.isNotEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            sourceParams = buildJsonObject { detailPath?.let { put("detail_path", it) } },
            data = parsed,
        )
    }

    suspend fun fetchEmptyRooms(
        term: String? = null,
        week: String? = null,
        building: String? = null,
        room: String? = null,
    ): ModuleEnvelope<EmptyRoomData> {
        val params = mutableMapOf<String, String?>()
        term?.let { params["zxjxjhh"] = it }
        params["zc"] = week ?: "8"
        building?.let { params["jxlh"] = it }
        room?.let { params["jash"] = it }
        params["has_advance_query"] = ""
        val html = getText("/classroom/timeholdresult/room_view/", params)
        val parsed = parseEmptyRooms(
            html,
            mapOf("term" to term, "week" to params["zc"], "building" to building, "room" to room)
                .filterValues { !it.isNullOrBlank() },
        )
        return ModuleEnvelope(
            module = "empty_rooms",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                parsed.query.forEach { (key, value) -> value?.let { put(key, it) } }
            },
            data = parsed,
        )
    }

    private suspend fun getText(path: String, params: Map<String, String?> = emptyMap()): String {
        val url = if (path.startsWith("http")) path else ProviderConstants.AA_BASE_URL + path
        val response = client.getText(url, params)
        val head = response.body.take(4096)
        if (response.url.contains("/client/login/") || (head.contains("用户登录") && head.contains("教学支撑平台"))) {
            throw SessionExpiredException("教学支撑平台未登录，请重新登录。")
        }
        return response.body
    }
}
